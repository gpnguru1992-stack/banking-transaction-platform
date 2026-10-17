package com.bank.common.event;

public final class EventTypes {

    public static final String TRANSFER_COMPLETED = "TransferCompleted";
    public static final String TRANSFER_FAILED = "TransferFailed";
    public static final String ACCOUNT_OPENED = "AccountOpened";
    public static final String FUNDS_DEPOSITED = "FundsDeposited";

    public static final String INTERBANK_TRANSFER_INITIATED = "InterbankTransferInitiated";
    public static final String INTERBANK_TRANSFER_SETTLED = "InterbankTransferSettled";
    public static final String INTERBANK_TRANSFER_REFUNDED = "InterbankTransferRefunded";

    public static final String CLEARING_SETTLED = "InterbankClearingSettled";
    public static final String CLEARING_REJECTED = "InterbankClearingRejected";

    public static final String CUSTOMER_REGISTERED = "CustomerRegistered";
    public static final String CUSTOMER_UPDATED = "CustomerUpdated";

    private EventTypes() {
    }
}
