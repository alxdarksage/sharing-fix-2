package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import org.joda.time.DateTime;

public final class UserInfo {
    final String studyId;
    final String id;
    final DateTime createdOn;
    public UserInfo(String studyId, String id, DateTime createdOn) {
        this.studyId = studyId;
        this.id = id;
        this.createdOn = createdOn;
    }
    public String getStudyId() {
        return studyId;
    }
    public String getId() {
        return id;
    }
    public DateTime getCreatedOn() {
        return createdOn;
    }
    @Override
    public String toString() {
        return "UserInfo [studyId=" + studyId + ", id=" + id + ", createdOn=" + createdOn + "]";
    }
}
