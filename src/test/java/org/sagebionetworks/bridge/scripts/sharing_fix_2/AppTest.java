package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.sagebionetworks.bridge.rest.model.Environment.PRODUCTION;

import java.util.ArrayList;
import java.util.List;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.PredefinedClientConfigurations;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.google.common.collect.ImmutableList;

import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.Config;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.Upload;
import org.sagebionetworks.bridge.rest.model.UploadList;

public class AppTest extends Mockito {
    private static final String USER_ID = "user1";
    private static final DateTime NOW = DateTime.now();
    private static final DateTime CREATED_ON = NOW.minusHours(2);
    
    @Mock
    BasicAWSCredentials mockCredentials;
    
    @Mock
    ClientManager mockManager;
    
    @Mock
    ParticipantsApi mockUsersApi;
    
    @Mock
    ForAdminsApi mockAdminApi;
    
    @Mock
    UploadList mockUploadList1;
    
    @Mock
    UploadList mockUploadList2;
    
    List<UserInfo> users;
    
    SignIn signIn;
    
    @Before
    public void before( ) {
        DateTimeUtils.setCurrentMillisFixed(NOW.getMillis());
        
        users = new ArrayList<>();
        users.add(new UserInfo("study1", "user1", CREATED_ON));
        users.add(new UserInfo("study1", "user2", CREATED_ON));
        users.add(new UserInfo("study2", "user2", CREATED_ON));
        
        signIn = new SignIn().email("email@email.com").password("password").study("api");
        
        MockitoAnnotations.initMocks(this);
    }
    
    @After
    public void after() {
        DateTimeUtils.setCurrentMillisSystem();
    }
    
    @Test
    public void test() {
        App app = spy(new App(users, signIn, mockCredentials));
        when(app.createClientManager()).thenReturn(mockManager);
    }
    
    @Test
    public void getDynamoClient() {
        App app = new App(users, signIn, mockCredentials);
        AmazonDynamoDBClient client = app.getDynamoClient();
        assertNotNull(client);
    }
    
    @Test
    public void createClientManager() {
        App app = new App(users, signIn, mockCredentials);
        ClientManager manager = app.createClientManager();
        assertNotNull(manager);
    }
    
    @Test
    public void healthDataRecordsToChange() throws Exception { 
        App app = spy(new App(users, signIn, mockCredentials));
        
        DateTime start = new DateTime(app.EVENT_START);
        assertEquals(start, app.EVENT_START);
        DateTime end = NOW.plusHours(1);
        
        Upload upload1 = new Upload();
        Tests.setVariableValueInObject(upload1, "recordId", "id1");
        Upload upload2 = new Upload();
        Tests.setVariableValueInObject(upload2, "recordId", "id2");
        Upload upload3 = new Upload();
        // upload3 does not have a recordId and will not be added to list.
        Upload upload4 = new Upload();
        Tests.setVariableValueInObject(upload4, "recordId", "id4");
        when(mockUploadList1.getItems()).thenReturn(ImmutableList.of(upload1, upload2, upload3));
        when(mockUploadList1.getNextPageOffsetKey()).thenReturn("anOffset");
        
        when(mockUploadList2.getItems()).thenReturn(ImmutableList.of(upload4));
        /*
        doReturn(mockUploadList1).when(app).getParticipantUploads(USER_ID, start, end, null);
        doReturn(mockUploadList2).when(app).getParticipantUploads(USER_ID, start, end, "anOffset");
        
        List<String> recordIds = app.healthDataRecordsToChange(USER_ID);
        assertEquals(recordIds, ImmutableList.of("id1", "id2", "id4"));
        
        verify(app).getParticipantUploads(mockUsersApi, USER_ID, start, end, null);
        */
    }
    
}
