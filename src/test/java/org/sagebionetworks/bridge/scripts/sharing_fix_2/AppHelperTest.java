package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import static com.amazonaws.services.dynamodbv2.model.ReturnValue.NONE;
import static org.junit.Assert.assertEquals;
import static org.sagebionetworks.bridge.rest.model.SharingScope.ALL_QUALIFIED_RESEARCHERS;
import static org.sagebionetworks.bridge.rest.model.SharingScope.NO_SHARING;
import static org.sagebionetworks.bridge.scripts.sharing_fix_2.Tests.setVariableValueInObject;

import java.io.IOException;

import com.amazonaws.services.dynamodbv2.document.KeyAttribute;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.verification.Calls;

import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Upload;
import org.sagebionetworks.bridge.rest.model.UploadList;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;

import retrofit2.Call;
import retrofit2.Response;

public class AppHelperTest extends Mockito {

    @Mock
    Table mockTable;
    
    @Mock
    ForAdminsApi mockAdminApi;

    @Mock
    ParticipantsApi mockUserApi;
    
    @Captor
    ArgumentCaptor<UpdateItemSpec> updateItemSpecCaptor;

    AppHelper helper;
    
    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        helper = new AppHelper(mockTable, mockAdminApi, mockUserApi);
    }
    
    private <T> Call<T> makeCall(T type) {
        return retrofit2.mock.Calls.<T>response(Response.success(null));
    }
    
    @Test
    public void adminSignIn() throws IOException {
        SignIn signIn = new SignIn().email("email").password("password").study("study");
        when(mockAdminApi.adminSignIn(signIn)).thenReturn(makeCall(new UserSessionInfo()));
        
        helper.adminSignIn(signIn);
        
        verify(mockAdminApi).adminSignIn(signIn);
    }
    
    @Test
    public void adminChangeStudy() throws IOException {
        SignIn signIn = new SignIn().study("api");
        when(mockAdminApi.adminChangeStudy(signIn)).thenReturn(makeCall(new UserSessionInfo()));
        
        helper.adminChangeStudy("api");
        
        verify(mockAdminApi).adminChangeStudy(signIn);
    }
    
    @Test
    public void getParticipantById() throws IOException {
        when(mockUserApi.getParticipantById("userId", true)).thenReturn(makeCall(new StudyParticipant()));
        
        helper.getParticipantById("userId");
        
        verify(mockUserApi).getParticipantById("userId", true);
    }
    
    @Test
    public void getParticipantUploads() throws IOException {
        DateTime startTime = DateTime.now().minusHours(4);
        DateTime endTime = DateTime.now();
        when(mockUserApi.getParticipantUploads("userId", startTime, endTime, 50, "offsetKey"))
            .thenReturn(makeCall(new UploadList()));
        
        helper.getParticipantUploads("userId", startTime, endTime, "offsetKey");
        
        verify(mockUserApi).getParticipantUploads("userId", startTime, endTime, 50, "offsetKey");
    }
    
    @Test
    public void getUploadByRecordId() throws IOException {
        when(mockAdminApi.getUploadByRecordId("recordId")).thenReturn(makeCall(new Upload()));
        
        helper.getUploadByRecordId("recordId");
        
        verify(mockAdminApi).getUploadByRecordId("recordId");
    }
    
    @Test
    public void updateParticipant() throws IOException {
        StudyParticipant participant = new StudyParticipant();
        setVariableValueInObject(participant, "id", "userId");
        when(mockUserApi.updateParticipant("userId", participant)).thenReturn(makeCall(new Message()));
        
        helper.updateParticipant(participant);
        
        verify(mockUserApi).updateParticipant("userId", participant);
    }

    @Test
    public void changeHealthRecordSharingScope() {
        helper.changeHealthRecordSharingScope("oneRecordId", ALL_QUALIFIED_RESEARCHERS);

        verify(mockTable).updateItem(updateItemSpecCaptor.capture());
        UpdateItemSpec spec = updateItemSpecCaptor.getValue();
        
        KeyAttribute key = spec.getKeyComponents().iterator().next();
        assertEquals("id", key.getName());
        assertEquals("oneRecordId", key.getValue());
        assertEquals("set userSharingScope = :r", spec.getUpdateExpression());
        assertEquals("userSharingScope = :s", spec.getConditionExpression());
        assertEquals(NO_SHARING.name(), spec.getValueMap().get(":s"));
        assertEquals(ALL_QUALIFIED_RESEARCHERS.name(), spec.getValueMap().get(":r"));
        assertEquals(NONE.name(), spec.getReturnValues());
    }
    
    @Test
    public void changeHealthRecordSharingScopeConditionalCheckExceptionIsFine() {
        when(mockTable.updateItem(any())).thenThrow(new ConditionalCheckFailedException("test"));
        
        helper.changeHealthRecordSharingScope("recordId", ALL_QUALIFIED_RESEARCHERS);
        
        // It's fine. No exception was thrown.
    }
}
