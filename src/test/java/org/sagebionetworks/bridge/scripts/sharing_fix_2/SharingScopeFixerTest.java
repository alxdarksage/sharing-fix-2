package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.sagebionetworks.bridge.rest.model.AccountStatus.DISABLED;
import static org.sagebionetworks.bridge.rest.model.AccountStatus.ENABLED;
import static org.sagebionetworks.bridge.rest.model.AccountStatus.UNVERIFIED;
import static org.sagebionetworks.bridge.rest.model.SharingScope.ALL_QUALIFIED_RESEARCHERS;
import static org.sagebionetworks.bridge.rest.model.SharingScope.NO_SHARING;
import static org.sagebionetworks.bridge.rest.model.SharingScope.SPONSORS_AND_PARTNERS;
import static org.sagebionetworks.bridge.scripts.sharing_fix_2.Tests.setVariableValueInObject;
import static org.sagebionetworks.bridge.util.IntegTestUtils.STUDY_ID;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.common.collect.ImmutableList;

import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import org.sagebionetworks.bridge.rest.model.HealthDataRecord;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Upload;
import org.sagebionetworks.bridge.rest.model.UploadList;

public class SharingScopeFixerTest extends Mockito {
    private static final String USER_ID_1 = "user1";
    private static final String USER_ID_2 = "user2";
    private static final String USER_ID_3 = "user3";
    
    @Mock
    AppHelper mockHelper;
    
    @Captor
    ArgumentCaptor<StudyParticipant> participantCaptor;
    
    List<UserInfo> users;
    
    SignIn signIn;
    
    SharingScopeFixer fixer;
    
    DateTime now;
    
    DateTime createdOn;
    
    @Before
    public void before( ) {
        DateTimeUtils.setCurrentMillisFixed(DateTime.now().getMillis());
        
        now = DateTime.now();
        createdOn = now.minusHours(2);
        
        MockitoAnnotations.initMocks(this);
        
        users = new ArrayList<>();
        users.add(new UserInfo("study1", USER_ID_1, createdOn));
        
        signIn = new SignIn().email("email@email.com").password("password").study("api");
        
        fixer = spy(new SharingScopeFixer(users, signIn, mockHelper));
    }
    
    @After
    public void after() {
        DateTimeUtils.setCurrentMillisSystem();
    }
    
    @Test
    public void runSkipsDisabledParticipant() throws Exception {
        StudyParticipant participant = new StudyParticipant().status(DISABLED).consented(true);
        when(mockHelper.getParticipantById(any())).thenReturn(participant);
        
        fixer.run();
        
        verify(fixer, never()).findPriorSharingScope(any(), any());
        
        // it does change study, it has to
        verify(mockHelper).adminChangeStudy("study1");
    }
    
    @Test
    public void runSkipsUnverifiedParticipant() throws Exception {
        StudyParticipant participant = new StudyParticipant().status(UNVERIFIED).consented(true);
        when(mockHelper.getParticipantById(any())).thenReturn(participant);
        
        fixer.run();
        
        verify(fixer, never()).findPriorSharingScope(any(), any());
    }
    
    @Test
    public void runSkipsUnconsentedParticipant() throws Exception {
        StudyParticipant participant = new StudyParticipant().status(ENABLED).consented(false);
        when(mockHelper.getParticipantById(any())).thenReturn(participant);
        
        fixer.run();
        
        verify(fixer, never()).findPriorSharingScope(any(), any());
    }
    
    @Test
    public void runSkipsUploadSearchIfUserCreatedAfterDeployment() throws Exception {
        StudyParticipant participant = new StudyParticipant().status(ENABLED).consented(true);
        setVariableValueInObject(participant, "createdOn", fixer.EVENT_START.plusMinutes(10));
        when(mockHelper.getParticipantById(any())).thenReturn(participant);
        
        UploadList list = mockList(null, 
                createUpload(null, now.minusDays(3), null),
                createUpload(null, now.minusDays(2), null),
                createUpload("id1", now.minusDays(1), null),
                createUpload(null, now, null));
        when(mockHelper.getParticipantUploads(eq(USER_ID_1), any(), any(), any()))
            .thenReturn(list);
        
        fixer.run();
        
        verify(fixer, never()).findPriorSharingScope(any(), any());
        
        // But it does correct the participant and records with default sharing scope
        verify(mockHelper).updateParticipant(participantCaptor.capture());
        assertEquals(SPONSORS_AND_PARTNERS, participantCaptor.getValue().getSharingScope());
        verify(mockHelper).changeHealthRecordSharingScope("id1", SPONSORS_AND_PARTNERS);
    }
    
    @Test
    public void runUsesDifferentDefaultSharingForSamsung() throws Exception {
        fixer.users.set(0, new UserInfo("samsung-blood-pressure", USER_ID_1, createdOn));
        StudyParticipant participant = new StudyParticipant().status(ENABLED).consented(true);
        setVariableValueInObject(participant, "createdOn", createdOn);
        
        when(mockHelper.getParticipantById(any())).thenReturn(participant);
        
        UploadList list = mockList(null, 
                createUpload("id1", now.minusDays(1), null));
        when(mockHelper.getParticipantUploads(eq(USER_ID_1), any(), any(), any()))
            .thenReturn(list);
        
        fixer.run();
        
        // But it does correct the participant and records with default sharing scope
        verify(mockHelper).updateParticipant(participantCaptor.capture());
        assertEquals(ALL_QUALIFIED_RESEARCHERS, participantCaptor.getValue().getSharingScope());
        verify(mockHelper).changeHealthRecordSharingScope("id1", ALL_QUALIFIED_RESEARCHERS);
    }
    
    @Test
    public void runIteratesThroughAllUsers() throws Exception {
        users = new ArrayList<>();
        users.add(new UserInfo("study1", USER_ID_1, createdOn));
        users.add(new UserInfo("study2", USER_ID_2, createdOn));
        users.add(new UserInfo("study2", USER_ID_3, createdOn));
        
        fixer = spy(new SharingScopeFixer(users, signIn, mockHelper));
        doReturn(ImmutableList.of()).when(fixer).getHealthRecordsToChange(any());
        doReturn(ALL_QUALIFIED_RESEARCHERS).when(fixer).findPriorSharingScope(any(), any());
        
        StudyParticipant participant1 = new StudyParticipant().status(ENABLED).consented(true);
        setVariableValueInObject(participant1, "createdOn", createdOn.minusDays(100));
        
        StudyParticipant participant2 = new StudyParticipant().status(ENABLED).consented(true);
        setVariableValueInObject(participant2, "createdOn", createdOn.minusDays(100));
        
        StudyParticipant participant3 = new StudyParticipant().status(ENABLED).consented(true);
        setVariableValueInObject(participant3, "createdOn", createdOn.minusDays(100));
        
        when(mockHelper.getParticipantById(USER_ID_1)).thenReturn(participant1);
        when(mockHelper.getParticipantById(USER_ID_2)).thenReturn(participant2);
        when(mockHelper.getParticipantById(USER_ID_3)).thenReturn(participant3);
        
        fixer.run();
        
        InOrder inOrder = inOrder(mockHelper);
        inOrder.verify(mockHelper).adminChangeStudy("study1");
        inOrder.verify(mockHelper).updateParticipant(participant1);
        inOrder.verify(mockHelper).adminChangeStudy("study2");
        inOrder.verify(mockHelper).updateParticipant(participant2);
        inOrder.verify(mockHelper).updateParticipant(participant3);
        
        assertEquals(ALL_QUALIFIED_RESEARCHERS, participant1.getSharingScope());
        assertEquals(ALL_QUALIFIED_RESEARCHERS, participant2.getSharingScope());
        assertEquals(ALL_QUALIFIED_RESEARCHERS, participant3.getSharingScope());
    }
    
    @Test
    public void runFindsNoSharingInUploadHistory() throws Exception {
        StudyParticipant participant = new StudyParticipant().status(ENABLED).consented(true);
        setVariableValueInObject(participant, "createdOn", createdOn.minusDays(100));
        when(mockHelper.getParticipantById(any())).thenReturn(participant);
        
        UploadList list = mockList(null,
            createUpload("id1", now.minusDays(3), ALL_QUALIFIED_RESEARCHERS), 
            createUpload("id2", now.minusDays(2), NO_SHARING));
        when(mockHelper.getParticipantUploads(any(), any(), any(), any())).thenReturn(list);

        fixer.run();
        
        // The upload records indicate that the user was sharing, but then turned sharing off
        // before the bug that left users in NO_SHARING state. We are going to leave that 
        // account in NO_SHARING state.
        verify(mockHelper, never()).updateParticipant(any());
        verify(mockHelper, never()).changeHealthRecordSharingScope(any(), any());
    }

    @Test
    public void runFindsNoCompletedUploads() throws Exception {
        StudyParticipant participant = new StudyParticipant().status(ENABLED).consented(true);
        setVariableValueInObject(participant, "createdOn", createdOn.minusDays(100));
        when(mockHelper.getParticipantById(any())).thenReturn(participant);
        
        UploadList list = mockList(null,
            createUpload("id1", now.minusDays(3), null), 
            createUpload(null, now.minusDays(2), null));
        when(mockHelper.getParticipantUploads(any(), any(), any(), any())).thenReturn(list);

        fixer.run();
        
        verify(mockHelper).updateParticipant(participantCaptor.capture());
        assertEquals(SPONSORS_AND_PARTNERS, participantCaptor.getValue().getSharingScope());
        
        verify(mockHelper).changeHealthRecordSharingScope("id1", SPONSORS_AND_PARTNERS);
    }
    
    @Test
    public void getHealthRecordsToChange() throws Exception { 
        DateTime start = new DateTime(fixer.EVENT_START);
        assertEquals(start, fixer.EVENT_START);
        DateTime end = now.plusHours(1);
        
        UploadList list1 = mockList("anOffset", 
                createUpload("id1", null, null),
                createUpload("id2", null, null),
                createUpload(null, null, null));
        UploadList list2 = mockList(null, 
                createUpload("id4", null, null));
        when(mockHelper.getParticipantUploads(USER_ID_1, start, end, null))
            .thenReturn(list1);
        when(mockHelper.getParticipantUploads(USER_ID_1, start, end, "anOffset"))
            .thenReturn(list2);
        
        List<String> recordIds = fixer.getHealthRecordsToChange(USER_ID_1);
        assertEquals(ImmutableList.of("id1", "id2", "id4"), recordIds);
    }
    
    @Test
    public void getHealthRecordsToChangeSkipsIncomplete() throws Exception { 
        DateTime start = new DateTime(fixer.EVENT_START);
        assertEquals(start, fixer.EVENT_START);
        DateTime end = now.plusHours(1);
        
        // I don't think you'll have a record ID without a health data record,
        // but we test for this condition
        UploadList list = mockList(null, 
                createUpload("id1", createdOn, ALL_QUALIFIED_RESEARCHERS),
                createIncompleteUpload(),
                createIncompleteUpload());
        when(mockHelper.getParticipantUploads(USER_ID_1, start, end, null)).thenReturn(list);
        
        List<String> recordIds = fixer.getHealthRecordsToChange(USER_ID_1);
        assertEquals(ImmutableList.of("id1"), recordIds);
    }
    
    @Test
    public void changeStudyNotNecessary() throws IOException {
        UserInfo userInfo = new UserInfo(STUDY_ID, USER_ID_1, createdOn);
        
        String returned = fixer.changeStudyIfNecessary(userInfo, STUDY_ID);
        assertEquals(STUDY_ID, returned);
        
        verify(mockHelper, never()).adminChangeStudy(any());
    }
    
    @Test
    public void changeStudyNecessary() throws IOException {
        UserInfo userInfo = new UserInfo(STUDY_ID, USER_ID_1, createdOn);
        
        String returned = fixer.changeStudyIfNecessary(userInfo, "differentStudy");
        assertEquals(STUDY_ID, returned);
        
        verify(mockHelper).adminChangeStudy(STUDY_ID);
    }
    
    @Test
    public void findPriorSharingScope() throws IOException {
        DateTime start = new DateTime(fixer.EVENT_START).minusDays(45).withZone(DateTimeZone.UTC);
        DateTime end = new DateTime(fixer.EVENT_START).withZone(DateTimeZone.UTC);
        
        // It might be that we don't need  
        UploadList list1 = mockList("anOffset",
            createUpload("id1", now.minusDays(3), NO_SHARING), 
            createUpload("id2", now.minusDays(2), NO_SHARING));
        UploadList list2 = mockList(null,
            createUpload("id4", now, ALL_QUALIFIED_RESEARCHERS));
        
        when(mockHelper.getParticipantUploads(USER_ID_1, start, end, null))
            .thenReturn(list1);
        when(mockHelper.getParticipantUploads(USER_ID_1, start, end, "anOffset"))
            .thenReturn(list2);
        
        SharingScope found = fixer.findPriorSharingScope(USER_ID_1, createdOn.minusDays(100));
        assertEquals(ALL_QUALIFIED_RESEARCHERS, found);
    }
    
    @Test
    public void findPriorSharingScopeCreatedAfterDefect() throws IOException {
        SharingScope found = fixer.findPriorSharingScope(USER_ID_1, fixer.EVENT_START.plusMinutes(30));
        assertNull(found);
        
        verify(mockHelper, never()).getParticipantUploads(any(), any(), any(), any());
        verify(mockHelper, never()).getUploadByRecordId(any());
    }
    
    @Test
    public void findPriorSharingScopeFailsToFindScope() throws IOException {
        // It might be that we don't need  
        UploadList list = mockList(null,
            createUpload("id1", now.minusDays(3), null), 
            createUpload(null, now.minusDays(2), null),
            createUpload("id3", now.minusDays(1), null),
            createUpload(null, now, null));
        when(mockHelper.getParticipantUploads(any(), any(), any(), any())).thenReturn(list);
        
        SharingScope found = fixer.findPriorSharingScope(USER_ID_1, createdOn.minusDays(100));
        assertNull(found);
        
        verify(mockHelper, times(2)).getParticipantUploads(any(), any(), any(), any());
        
        // Two times for each page (because we return the same list each call to getParticipantUploads
        verify(mockHelper, times(4)).getUploadByRecordId(any());
    }
    
    @Test
    public void findPriorSharingScopeSkipsIncompleteUploads() throws Exception {
        DateTime start = new DateTime(fixer.EVENT_START).minusDays(45).withZone(DateTimeZone.UTC);
        DateTime end = new DateTime(fixer.EVENT_START).withZone(DateTimeZone.UTC);
        
        // It might be that we don't need  
        UploadList list = mockList(null,
            createIncompleteUpload(),
            createUpload("id1", now.minusDays(3), SPONSORS_AND_PARTNERS), 
            createUpload("id2", now.minusDays(2), SPONSORS_AND_PARTNERS),
            createIncompleteUpload());
        when(mockHelper.getParticipantUploads(USER_ID_1, start, end, null)).thenReturn(list);
        
        SharingScope found = fixer.findPriorSharingScope(USER_ID_1, createdOn.minusDays(100));
        assertEquals(SPONSORS_AND_PARTNERS, found);
    }
    
    @Test
    public void findPriorSharingScopeHandlesCompletedRecordsWithoutSharingScope() throws Exception {
        DateTime start = new DateTime(fixer.EVENT_START).minusDays(45).withZone(DateTimeZone.UTC);
        DateTime end = new DateTime(fixer.EVENT_START).withZone(DateTimeZone.UTC);
        
        // It might be that we don't need  
        UploadList list = mockList(null,
            createUploadWithoutSharing("id1", now.minusDays(4)),
            createUpload("id2", now.minusDays(3), SPONSORS_AND_PARTNERS), 
            createUpload("id3", now.minusDays(2), SPONSORS_AND_PARTNERS),
            createUploadWithoutSharing("id4", now.minusDays(1)));
        when(mockHelper.getParticipantUploads(USER_ID_1, start, end, null)).thenReturn(list);
        
        SharingScope found = fixer.findPriorSharingScope(USER_ID_1, createdOn.minusDays(100));
        assertEquals(SPONSORS_AND_PARTNERS, found);
    }
    
    private Upload createUpload(String recordId, DateTime completedOn, SharingScope sharingScope) throws IOException {
        Upload upload = mock(Upload.class);
        if (recordId != null) {
            when(upload.getRecordId()).thenReturn(recordId);
            when(mockHelper.getUploadByRecordId(recordId)).thenReturn(upload);
        }
        when(upload.getCompletedOn()).thenReturn(completedOn);
        if (sharingScope != null) {
            HealthDataRecord record = mock(HealthDataRecord.class);
            when(record.getUserSharingScope()).thenReturn(sharingScope);
            when(upload.getHealthData()).thenReturn(record);
        }
        return upload;
    }
    
    private Upload createIncompleteUpload() throws IOException {
        return mock(Upload.class);
    }
    
    private Upload createUploadWithoutSharing(String recordId, DateTime completedOn) throws IOException {
        Upload upload = mock(Upload.class);
        if (recordId != null) {
            when(upload.getRecordId()).thenReturn(recordId);
            when(mockHelper.getUploadByRecordId(recordId)).thenReturn(upload);
        }
        when(upload.getCompletedOn()).thenReturn(completedOn);
        HealthDataRecord record = mock(HealthDataRecord.class);
        when(upload.getHealthData()).thenReturn(record);
        return upload;
    }
    
    
    private UploadList mockList(String offsetKey, Upload... uploads) {
        UploadList mock = mock(UploadList.class);
        when(mock.getItems()).thenReturn(Arrays.asList(uploads));
        when(mock.getNextPageOffsetKey()).thenReturn(offsetKey);
        return mock;
    }
}
