package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import com.amazonaws.auth.BasicAWSCredentials;
import com.google.common.collect.ImmutableList;

import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

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
    AppHelper mockHelper;
    
    @Mock
    UploadList mockUploadList1;
    
    @Mock
    UploadList mockUploadList2;
    
    List<UserInfo> users;
    
    SignIn signIn;
    
    App app;
    
    @Before
    public void before( ) {
        DateTimeUtils.setCurrentMillisFixed(NOW.getMillis());
        
        MockitoAnnotations.initMocks(this);
        
        users = new ArrayList<>();
        users.add(new UserInfo("study1", "user1", CREATED_ON));
        users.add(new UserInfo("study1", "user2", CREATED_ON));
        users.add(new UserInfo("study2", "user2", CREATED_ON));
        
        signIn = new SignIn().email("email@email.com").password("password").study("api");
        
        app = spy(new App(users, signIn, mockCredentials, mockHelper));
    }
    
    @After
    public void after() {
        DateTimeUtils.setCurrentMillisSystem();
    }
    
    @Test
    public void healthDataRecordsToChange() throws Exception { 
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
        
        when(mockHelper.getParticipantUploads(USER_ID, start, end, null)).thenReturn(mockUploadList1);
        when(mockHelper.getParticipantUploads(USER_ID, start, end, "anOffset")).thenReturn(mockUploadList2);
        
        List<String> recordIds = app.healthDataRecordsToChange(USER_ID);
        assertEquals(recordIds, ImmutableList.of("id1", "id2", "id4"));
    }
    
}
