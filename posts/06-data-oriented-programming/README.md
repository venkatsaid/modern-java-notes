# Stop Writing Visitors: Data-Oriented Programming in Java 21

*Modern Java, Explained Like We're Pairing · #6 · Java 21*

---

Let me show you a payment result the way I used to model it. Then I want you to spot the bug before I do.

```java
class PaymentResult {
    String status;        // "APPROVED", "DECLINED", "PENDING"
    String authCode;      // only set when approved... hopefully
    String reason;        // only set when declined
    Boolean retryable;    // only meaningful when declined
    Duration retryAfter;  // only when pending
}

String describe(PaymentResult r) {
    if (r.status.equals("APPROVED")) return "Paid, auth " + r.authCode;
    if (r.status.equals("DECLINED")) return "Declined: " + r.reason;
    return "Pending";
}
```

Found it? There are actually three:

1. Nothing stops `authCode` from being `null` on an approved payment.
2. Someone adds `"REFUNDED"` next quarter, and `describe` silently calls it "Pending".
3. `"APROVED"` with one P compiles perfectly.

The compiler can't help you, because you never told it what the valid shapes are. In this post we'll rebuild this the Java 21 way. The compiler will know every possible outcome, refuse to build when you forget one, and the code will get *shorter*. You'll also get a name for the approach that interviewers love: **data-oriented programming**.

---

## The idea in one sentence

Model your data as **plain, immutable values**, list **every shape it can take**, and write behaviour as **functions that switch over those shapes**.

Java 21 gives you one feature for each part of that sentence:

- **Plain, immutable values** → `record`
- **Every shape it can take** → `sealed interface ... permits`
- **Functions that switch over shapes** → pattern matching for `switch` + record patterns

Let's use them one at a time.

## Step 1: one record per shape

Instead of one class with five maybe-null fields, give each outcome exactly the data it has:

```java
public record Approved(String authCode, Money amount) implements PaymentResult {}
public record Declined(String reason, boolean retryable) implements PaymentResult {}
public record Pending(Duration retryAfter) implements PaymentResult {}
```

Look at what just disappeared. An `Approved` *can't* exist without an auth code; there's no field to forget. A `Pending` doesn't have a `reason` lying around as `null`. Bug #1 is gone by construction.

And records give you `equals`, `hashCode` and `toString` for free, so two `Money.usd("5.00")` values are equal, as money should be.

## Step 2: tell the compiler the list is closed

```java
public sealed interface PaymentResult permits Approved, Declined, Pending {}
```

That one word, `sealed`, is a promise: *these three are the only kinds of `PaymentResult` that will ever exist.* Nobody else can implement it.

Why would you want to close the door? Because now the compiler can reason about completeness. Hold that thought.

## Step 3: behaviour as a switch over shapes

Here's `describe`, rewritten:

```java
public static String describe(PaymentResult result) {
    return switch (result) {
        case Approved(var code, var amount) -> "Paid %s %s (auth %s)"
                .formatted(amount.amount(), amount.currency().getCurrencyCode(), code);
        case Declined(var reason, var retryable) when retryable -> "Declined, will retry: " + reason;
        case Declined(var reason, var retryable) -> "Declined: " + reason;
        case Pending(var after) -> "Pending, check again in " + after.toSeconds() + "s";
    };
}
```

A few things are happening here, so let's take them slowly:

- `case Approved(var code, var amount)` is a **record pattern**. It checks the type *and* pulls the fields out in one go. No cast, no getter calls.
- `when retryable` is a **guard**. It's like an `if` attached to a case.
- There's **no `default` branch**. That's on purpose, and it's the most important line in this post that you can't see.

## The moment it clicks

Quick experiment. Next quarter, the business adds refunds:

```java
public sealed interface PaymentResult permits Approved, Declined, Pending, Refunded {}
public record Refunded(Money amount) implements PaymentResult {}
```

You recompile. What do you think happens to `describe`?

It **fails to compile**. Here's the real output from `javac` 21:

```text
Payments.java:43: error: the switch expression does not cover all possible input values
        return switch (result) {
               ^
Payments.java:54: error: the switch expression does not cover all possible input values
        return switch (result) {
               ^
2 errors
```

Two errors, one for each function that needs to learn about refunds. The compiler just walked through your codebase and listed every place that needs to handle refunds. Bug #2, the silent "Pending", can't happen anymore.

> A `default` branch is you telling the compiler "don't worry about it". In data-oriented code, you *want* it to worry.

And bug #3, the typo'd string status? There are no strings. `Aproved` is a compile error.

## "But isn't this just the visitor pattern?"

Great question, and it's the one a senior interviewer will ask you.

The visitor pattern exists to do exactly this: add new operations over a fixed set of types without editing those types. It works, but you pay with an interface, an `accept` method on every class and a visitor class per operation.

With sealed types and pattern matching, a new operation is *just a method*:

```java
public static boolean shouldAlertOps(PaymentResult result) {
    return switch (result) {
        case Declined d when !d.retryable() -> true;
        case Pending(var after) when after.toMinutes() >= 10 -> true;
        case Approved a -> false;
        case Declined d -> false;
        case Pending p -> false;
    };
}
```

No changes to the records, no double dispatch, and the compiler still checks every case.

## When not to do this

Data-oriented programming isn't a replacement for every object:

- **Open hierarchies** (plugins, user-defined types) shouldn't be sealed; the point is that you don't know every implementation.
- **Rich behaviour with internal state** still belongs in classes with encapsulation. A `Ledger` that guards its invariants isn't a record.
- **JPA entities** need mutability and no-arg constructors, so keep records for DTOs, events, results and messages.

A good rule: if you'd describe the thing as *"it's one of these N kinds of data"*, reach for sealed records.

## Try it yourself

```bash
git clone https://github.com/venkatsaid/modern-java-notes
cd modern-java-notes
mvn test -Dtest=PaymentsTest
```

Then add a `Refunded` record to the `permits` list and watch the build tell you exactly what to fix. That compile error is the whole post in one red line.

## Wrapping up

Records give each shape exactly its own data, `sealed` makes the list of shapes complete, and pattern-matching `switch` turns behaviour into small functions that the compiler checks for completeness. Together they kill null-field bugs, forgotten cases and stringly-typed statuses.

Your next step: find one class in your code with a `status` or `type` field and a pile of maybe-null fields. Try remodelling it as a sealed interface plus records, even just on a branch.

What's the "status field with a dozen nullable friends" in your codebase?

---

**TL;DR**
- Model data as records, close the set with `sealed`, and write behaviour as exhaustive `switch` expressions.
- No `default` branch means adding a new case breaks the build exactly where you need to update code.
- It replaces most visitor-pattern code; keep classes for open hierarchies and stateful objects.

**Tags:** Java, Programming, Software Design, Clean Code, Software Development
