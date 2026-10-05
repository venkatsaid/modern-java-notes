package notes.p06;

import notes.p06.Payments.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static notes.p06.Payments.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentsTest {

    @Test
    void describesEveryOutcome() {
        assertEquals("Paid 25.00 USD (auth A1)", describe(new Approved("A1", Money.usd("25.00"))));
        assertEquals("Declined, will retry: issuer timeout", describe(new Declined("issuer timeout", true)));
        assertEquals("Declined: card stolen", describe(new Declined("card stolen", false)));
        assertEquals("Pending, check again in 30s", describe(new Pending(Duration.ofSeconds(30))));
    }

    @Test
    void alertsOnlyWhenOpsShouldCare() {
        assertTrue(shouldAlertOps(new Declined("card stolen", false)));
        assertFalse(shouldAlertOps(new Declined("issuer timeout", true)));
        assertTrue(shouldAlertOps(new Pending(Duration.ofMinutes(15))));
        assertFalse(shouldAlertOps(new Pending(Duration.ofSeconds(5))));
        assertFalse(shouldAlertOps(new Approved("A1", Money.usd("1.00"))));
    }

    @Test
    void moneyRejectsImpossibleAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.usd("10.001"));
    }

    @Test
    void recordsAreValuesNotIdentities() {
        assertEquals(Money.usd("5.00"), Money.usd("5.00"));
    }
}
