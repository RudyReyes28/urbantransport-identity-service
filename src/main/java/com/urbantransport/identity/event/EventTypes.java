package com.urbantransport.identity.event;

public final class EventTypes {

    public static final String AGGREGATE_BRANCH = "Branch";
    public static final String AGGREGATE_USER = "User";

    public static final String BRANCH_CREATED = "BranchCreated";
    public static final String BRANCH_UPDATED = "BranchUpdated";
    public static final String USER_CREATED = "UserCreated";
    public static final String USER_UPDATED = "UserUpdated";
    public static final String USER_DEACTIVATED = "UserDeactivated";
    public static final String PASSWORD_SETUP_REQUESTED = "PasswordSetupRequested";

    public static final String CUSTOMER_NIT_CHANGED = "CustomerNitChanged";

    public static final String DRIVER_DEACTIVATED = "DriverDeactivated";

    private EventTypes() {
    }
}