package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import static java.lang.Boolean.TRUE;
import static org.joda.time.DateTimeZone.UTC;
import static org.sagebionetworks.bridge.rest.model.AccountStatus.ENABLED;
import static org.sagebionetworks.bridge.rest.model.Environment.PRODUCTION;
import static org.sagebionetworks.bridge.rest.model.SharingScope.ALL_QUALIFIED_RESEARCHERS;
import static org.sagebionetworks.bridge.rest.model.SharingScope.SPONSORS_AND_PARTNERS;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.PredefinedClientConfigurations;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;

import org.joda.time.DateTime;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.Config;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Upload;
import org.sagebionetworks.bridge.rest.model.UploadList;

public class App {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TABLE_NAME = "prod-heroku-HealthDataRecord3";
    
    private List<UserInfo> users;
    private SignIn signIn;
    // 2019-08-29T14:45:05-07:00 is the timestamp of the broken deployment
    private DateTime eventStart = new DateTime(1567115105000L);
    // 2019-09-19T23:24:02.000Z is the timestamp of the deployment of a fix (in query for users below)
    private BasicAWSCredentials awsCredentials;

    public static void main(String[] args) throws Exception {
        // These users are the people retrieved with this query:
        // SELECT studyId, id, createdOn 
        // FROM Accounts a
        // WHERE sharingScope = 'NO_SHARING' 
        // AND status = 'ENABLED' 
        // AND modifiedOn > 1567115105000 
        // AND modifiedOn < 1568935442000
        // AND (SELECT count(*) FROM AccountDataGroups ag WHERE ag.accountId = a.id AND ag.dataGroup = 'test_user') = 0
        // LIMIT 2350;
        JsonNode users = MAPPER.readTree(new File("/Users/adark/temp/impacted-users.json"));
        List<UserInfo> list = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            JsonNode user = users.get(i);
            String studyId = user.get("studyId").textValue();
            String id = user.get("id").textValue();
            DateTime createdOn = new DateTime(user.get("createdOn").longValue()).withZone(UTC);
            list.add(new UserInfo(studyId, id, createdOn));
        }
        JsonNode config = MAPPER.readTree(new File("/Users/adark/sharing-fix.json"));
        SignIn signIn = new SignIn()
                .study("api")
                .email(config.get("admin").get("email").textValue())
                .password(config.get("admin").get("password").textValue());
        
        BasicAWSCredentials awsCredentials = new BasicAWSCredentials(
                config.get("aws").get("key").textValue(),
                config.get("aws").get("secret").textValue());
        new App(list, signIn, awsCredentials).run();
    }

    public App(List<UserInfo> users, SignIn signIn, BasicAWSCredentials awsCredentials) {
        this.users = users;
        this.signIn = signIn;
        this.awsCredentials = awsCredentials;
    }
    
    public void run() throws Exception {
        AmazonDynamoDBClient client = getDynamoClient();
        Table table = new DynamoDB(client).getTable(TABLE_NAME);
        ClientManager manager = createClientManager();
        ForAdminsApi adminsApi = manager.getClient(ForAdminsApi.class);

        adminsApi.adminSignIn(signIn).execute().body();
        
        String currentStudyId = "api";
        for (UserInfo userInfo : users) {
            currentStudyId = changeStudyIfNecessary(adminsApi, userInfo, currentStudyId);
            ParticipantsApi usersApi = manager.getClient(ParticipantsApi.class);
            StudyParticipant participant = usersApi.getParticipantById(userInfo.getId(), true).execute().body();

            // If the user isn't enabled or isn't consented, don't turn on their sharing status
            if (participant.getStatus() != ENABLED || !TRUE.equals(participant.isConsented())) {
                continue;
            }
            // The sharing scope we'll use if we can't determine a more accurate value from historical records.
            SharingScope scope = ("samsung-blood-pressure".equals(userInfo.getStudyId())) ?
                    ALL_QUALIFIED_RESEARCHERS :
                    SPONSORS_AND_PARTNERS;
            
            // If user was created *after* bug was introduced, there's no point in looking in history for a better
            // choice of sharing scope
            if (participant.getCreatedOn().isBefore(eventStart)) {
                // Query health data records for a previous value
                SharingScope foundScope = findPriorSharingScope(adminsApi, usersApi, userInfo.getId(), participant.getCreatedOn());
                if (foundScope != null) {
                    scope = foundScope;
                }
            }
            if (scope != SharingScope.NO_SHARING) {
                participant.setSharingScope(scope);
                System.out.println("UPDATE USER: " + userInfo.getId() + " to sharing " + scope);
                usersApi.updateParticipant(userInfo.getId(), participant).execute().body();
                
                List<String> recordIds = recordsToChange(usersApi, userInfo.getId());
                System.out.println("UPDATE HEALTH RECORDS: " + Joiner.on(", ").join(recordIds));
                for (String recordId : recordIds) {
                    GetItemRequest request = new GetItemRequest();
                    request.setTableName(TABLE_NAME);
                    request.setKey(ImmutableMap.of("id", new AttributeValue().withS(recordId)));
                    GetItemResult result = client.getItem(request);
                    
                    if (result.getItem().get("userSharingScope").getS().equals("NO_SHARING")) {
                        UpdateItemSpec updateItemSpec = new UpdateItemSpec().withPrimaryKey("id", recordId)
                                .withUpdateExpression("set userSharingScope = :r")
                                .withValueMap(new ValueMap().withString(":r", scope.name()))
                                .withReturnValues(ReturnValue.UPDATED_NEW);
                        table.updateItem(updateItemSpec);
                    }
                }
            }
            Thread.sleep(1000);
        }
    }
    
    @SuppressWarnings("deprecation")
    AmazonDynamoDBClient getDynamoClient() {
        ClientConfiguration awsClientConfig = PredefinedClientConfigurations.dynamoDefault().withMaxErrorRetry(1);
        return new AmazonDynamoDBClient(awsCredentials, awsClientConfig);
    }
    
    ClientManager createClientManager() {
        Config bridgeConfig = new Config();
        bridgeConfig.set(PRODUCTION);
        return new ClientManager.Builder().withSignIn(signIn).withConfig(bridgeConfig).build();
    }
    
    String changeStudyIfNecessary(ForAdminsApi adminsApi, UserInfo userInfo, String currentStudyId) throws Exception {
        if (currentStudyId.equals(userInfo.getStudyId())) {
            return currentStudyId;
        }
        adminsApi.adminChangeStudy(new SignIn().study(userInfo.getStudyId())).execute().body();
        return userInfo.getStudyId();
    }
    
    List<String> recordsToChange(ParticipantsApi usersApi, String userId) throws Exception {
        DateTime start = new DateTime(eventStart);
        DateTime end = DateTime.now();
        List<String> healthRecordIds = new ArrayList<>();
        String offsetKey = null;
        do {
            UploadList list = usersApi.getParticipantUploads(userId, start, end, 50, offsetKey).execute().body();
            for (Upload upload : list.getItems()) {
                if (upload.getRecordId() != null) {
                    healthRecordIds.add(upload.getRecordId());
                }
            }
            offsetKey = list.getNextPageOffsetKey();
        } while(offsetKey != null);
        
        return healthRecordIds;
    }
    
    
    SharingScope findPriorSharingScope(ForAdminsApi adminsApi, ParticipantsApi usersApi, String userId, DateTime createdOn) throws Exception {
        // We have to be super aggressive about this. The user was created before the sharing error, we need to figure
        // out what their sharing setting was...
        int daysStart = 0;
        int daysEnd = 0;
        while(eventStart.minusDays(daysStart).isAfter(createdOn)) {
            daysEnd = daysStart;
            daysStart += 45;
            if (eventStart.minusDays(daysStart).isBefore(createdOn)) {
                return null;
            }
            String offsetKey = null;
            List<Upload> allUploadsInDateRange = new ArrayList<>();
            do {
                DateTime startTime = eventStart.minusDays(daysStart).withZone(UTC);
                DateTime endTime = eventStart.minusDays(daysEnd).withZone(UTC);
                
                UploadList list = usersApi.getParticipantUploads(userId, startTime, endTime, 50, offsetKey).execute().body();
                allUploadsInDateRange.addAll(list.getItems());
                offsetKey = list.getNextPageOffsetKey();
            } while(offsetKey != null);
            
            List<Upload> sorted = allUploadsInDateRange.stream()
                .filter(up -> up.getCompletedOn() != null)
                .sorted(Comparator.comparing(Upload::getCompletedOn).reversed())
                .collect(Collectors.toList());
            for (Upload upload : sorted) {
                if (upload.getRecordId() != null) {
                    Upload fullUpload = adminsApi.getUploadByRecordId(upload.getRecordId()).execute().body();
                    if (fullUpload.getHealthData().getUserSharingScope() != null) {
                        SharingScope found = fullUpload.getHealthData().getUserSharingScope();
                        return found;
                    }
                }
            }
        }
        return null;
    }
}
