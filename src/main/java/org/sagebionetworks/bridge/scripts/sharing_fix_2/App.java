package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import static org.joda.time.DateTimeZone.UTC;
import static org.sagebionetworks.bridge.rest.model.Environment.PRODUCTION;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.PredefinedClientConfigurations;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.joda.time.DateTime;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.Config;
import org.sagebionetworks.bridge.rest.Config.Props;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.SignIn;

public class App {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    AppHelper helper;
    List<UserInfo> users;
    SignIn signIn;

    public static void main(String[] args) throws Exception {
        JsonNode config = MAPPER.readTree(new File("/Users/adark/sharing-fix.json"));
        
        // These users are the people retrieved with this query:
        // SELECT studyId, id, createdOn FROM Accounts a
        // WHERE sharingScope = 'NO_SHARING' AND status = 'ENABLED' 
        // AND modifiedOn > 1567115105000  AND modifiedOn < 1568935442000
        // AND (SELECT count(*) FROM AccountDataGroups ag WHERE ag.accountId = a.id AND ag.dataGroup = 'test_user') = 0
        // LIMIT 2350;
        JsonNode users = MAPPER.readTree(new File(config.get("file").textValue()));
        List<UserInfo> list = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            JsonNode user = users.get(i);
            String studyId = user.get("studyId").textValue();
            String id = user.get("id").textValue();
            DateTime createdOn = new DateTime(user.get("createdOn").longValue()).withZone(UTC);
            list.add(new UserInfo(studyId, id, createdOn));
        }
        
        BasicAWSCredentials awsCredentials = new BasicAWSCredentials(
                config.get("aws").get("key").textValue(),
                config.get("aws").get("secret").textValue());
        AmazonDynamoDBClient client = getDynamoClient(awsCredentials);
        
        SignIn signIn = new SignIn().study("api")
                .email(config.get("admin").get("email").textValue())
                .password(config.get("admin").get("password").textValue());
        ClientManager manager = createClientManager(signIn);
        
        AppHelper helper = new AppHelper(client, manager.getClient(ForAdminsApi.class),
                manager.getClient(ParticipantsApi.class));
        
        new SharingScopeFixer(list, signIn, helper).run();
    }
    
    static ClientManager createClientManager(SignIn signIn) {
        Config bridgeConfig = new Config();
        bridgeConfig.set(PRODUCTION);
        bridgeConfig.set(Props.LANGUAGES, "en");
        return new ClientManager.Builder().withSignIn(signIn).withConfig(bridgeConfig).build();
    }
    
    @SuppressWarnings("deprecation")
    static AmazonDynamoDBClient getDynamoClient(BasicAWSCredentials awsCredentials) {
        ClientConfiguration awsClientConfig = PredefinedClientConfigurations.dynamoDefault().withMaxErrorRetry(1);
        return new AmazonDynamoDBClient(awsCredentials, awsClientConfig);
    }

}
