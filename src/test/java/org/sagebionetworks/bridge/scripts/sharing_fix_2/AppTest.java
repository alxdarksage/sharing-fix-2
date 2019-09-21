package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import java.util.ArrayList;
import java.util.List;

import com.amazonaws.auth.BasicAWSCredentials;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.model.SignIn;

public class AppTest extends Mockito {

    private static final DateTime CREATED_ON = DateTime.now();
    
    @Mock
    BasicAWSCredentials mockCredentials;
    
    @Mock
    ClientManager mockManager;
    
    @Before
    public void before( ) {
        MockitoAnnotations.initMocks(this);
    }
    
    @Test
    public void test() {
        List<UserInfo> users = new ArrayList<>();
        users.add(new UserInfo("study1", "user1", CREATED_ON));
        users.add(new UserInfo("study1", "user2", CREATED_ON));
        users.add(new UserInfo("study2", "user2", CREATED_ON));
        
        SignIn signIn = new SignIn().email("email@email.com").password("password").study("api");
        
        App app = spy(new App(users, signIn, mockCredentials));
        when(app.createClientManager()).thenReturn(mockManager);
    }
    
}
