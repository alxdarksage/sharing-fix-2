package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import static org.sagebionetworks.bridge.rest.model.SharingScope.NO_SHARING;

import java.io.IOException;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;

import org.joda.time.DateTime;

import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Upload;
import org.sagebionetworks.bridge.rest.model.UploadList;

public class AppHelper {
    private static final String TABLE_NAME = "prod-heroku-HealthDataRecord3";
    private final ForAdminsApi adminsApi;
    private final ParticipantsApi usersApi;
    private final Table table;
    
    public AppHelper(AmazonDynamoDBClient client, ForAdminsApi adminsApi, ParticipantsApi usersApi) {
        this.adminsApi = adminsApi;
        this.usersApi = usersApi;
        this.table = new DynamoDB(client).getTable(TABLE_NAME);
    }
    
    public void adminSignIn(SignIn signIn) throws IOException {
        adminsApi.adminSignIn(signIn).execute().body();
    }
    
    public void adminChangeStudy(String studyId) throws IOException {
        adminsApi.adminChangeStudy(new SignIn().study(studyId)).execute().body();
    }
    
    public StudyParticipant getParticipantById(String userId) throws IOException {
        return usersApi.getParticipantById(userId, true).execute().body();
    }
    
    public UploadList getParticipantUploads(String userId, DateTime startTime, DateTime endTime, String offsetKey) throws IOException {
        return usersApi.getParticipantUploads(userId, startTime, endTime, 50, offsetKey).execute().body();
    }
    
    public Upload getUploadByRecordId(String recordId) throws IOException {
        return adminsApi.getUploadByRecordId(recordId).execute().body();
    }
    
    public void updateParticipant(StudyParticipant participant) throws IOException {
        // usersApi.updateParticipant(participant.getId(), participant).execute().body(); 
    }
    
    public void changeHealthRecordSharingScope(String recordId, SharingScope scope) {
        UpdateItemSpec updateItemSpec = new UpdateItemSpec()
                .withPrimaryKey("id", recordId)
                .withUpdateExpression("set userSharingScope = :r")
                .withConditionExpression("userSharingScope <> :s") // or is it != ?
                .withValueMap(new ValueMap().withString(":r", scope.name()).withString(":s", NO_SHARING.name()))
                .withReturnValues(ReturnValue.NONE);
        // table.updateItem(updateItemSpec);
    }
}
