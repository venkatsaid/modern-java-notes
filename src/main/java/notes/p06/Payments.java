package notes.p06;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.Objects;

/**
 * Post #6 — "Data-oriented programming in Java 21".
 * The data: small immutable records. The set of cases: a sealed interface.
 * The behaviour: plain functions that switch over the data.
 */
public final class Payments {

    private Payments() {}

    public record Money(BigDecimal amount, Currency currency) {
        public Money {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
            if (amount.scale() > currency.getDefaultFractionDigits()) {
                throw new IllegalArgumentException("too many decimal places for " + currency);
            }
        }

        public static Money usd(String amount) {
            return new Money(new BigDecimal(amount), Currency.getInstance("USD"));
        }
    }

    /** Every possible outcome of authorizing a payment. The compiler knows this list is complete. */
    public sealed interface PaymentResult permits Approved, Declined, Pending {}

    public record Approved(String authCode, Money amount) implements PaymentResult {}

    public record Declined(String reason, boolean retryable) implements PaymentResult {}

    public record Pending(Duration retryAfter) implements PaymentResult {}

    /** Behaviour lives outside the data, in one exhaustive switch. No default branch on purpose. */
    public static String describe(PaymentResult result) {
        return switch (result) {
            case Approved(var code, var amount) -> "Paid %s %s (auth %s)"
                    .formatted(amount.amount(), amount.currency().getCurrencyCode(), code);
            case Declined(var reason, var retryable) when retryable -> "Declined, will retry: " + reason;
            case Declined(var reason, var retryable) -> "Declined: " + reason;
            case Pending(var after) -> "Pending, check again in " + after.toSeconds() + "s";
        };
    }

    /** A second operation over the same data, written without touching the records. */
    public static boolean shouldAlertOps(PaymentResult result) {
        return switch (result) {
            case Declined d when !d.retryable() -> true;
            case Pending(var after) when after.toMinutes() >= 10 -> true;
            case Approved a -> false;
            case Declined d -> false;
            case Pending p -> false;
        };
    }
}
