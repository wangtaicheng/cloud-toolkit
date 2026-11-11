package com.cloudtoolkit.ecsdeploy.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransferProgressTest {

    @Test
    void zeroTotalHasZeroPercent() {
        assertEquals(0, new TransferProgress(5, 0).percent());
    }

    @Test
    void computesPercentage() {
        assertEquals(50, new TransferProgress(5, 10).percent());
    }

    @Test
    void clampsPercentageAtHundred() {
        assertEquals(100, new TransferProgress(20, 10).percent());
    }
}
