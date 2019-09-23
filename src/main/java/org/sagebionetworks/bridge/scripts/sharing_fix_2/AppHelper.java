package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import java.io.IOException;

import org.joda.time.DateTime;

import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Upload;
import org.sagebionetworks.bridge.rest.model.UploadList;

public class AppHelper {
    private final ForAdminsApi adminsApi;
    private final ParticipantsApi usersApi;

    public AppHelper(ForAdminsApi adminsApi, ParticipantsApi usersApi) {
        this.adminsApi = adminsApi;
        this.usersApi = usersApi;
    }
    
    public void adminSignIn(String studyId) throws IOException {
        adminsApi.adminSignIn(new SignIn().study(studyId)).execute().body();
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
}
