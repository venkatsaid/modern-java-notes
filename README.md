# Modern Java, Explained Like We're Pairing

Short, conversational posts on advanced Java 21+, each backed by code you can run. Every example in a post is a passing JUnit test in this repo.

![Java 21](https://img.shields.io/badge/Java-21-blue) ![build](https://github.com/venkatsaid/modern-java-notes/actions/workflows/build.yml/badge.svg)

## The series

| # | Post | Code | Medium |
| --- | --- | --- | --- |
| 1 | [10,000 threads, one second: what a virtual thread really does](posts/01-how-virtual-threads-work/README.md) | [`VirtualThreadBasicsTest`](src/test/java/notes/p01/VirtualThreadBasicsTest.java) | *coming soon* |
| 2 | [The keyword that quietly pins your virtual threads](posts/02-pinning/README.md) | [`PinningTest`](src/test/java/notes/p02/PinningTest.java) | *coming soon* |
| 6 | [Stop writing visitors: data-oriented programming in Java 21](posts/06-data-oriented-programming/README.md) | [`Payments`](src/main/java/notes/p06/Payments.java) · [`PaymentsTest`](src/test/java/notes/p06/PaymentsTest.java) | *coming soon* |

### Coming up

3 · Don't pool virtual threads, limit them · 4 · ThreadLocal at a million threads · 5 · Structured concurrency · 7 · Exhaustiveness and `MatchException` · 8 · Dominance and guards · 9 · Nested record patterns · 10 · Sealed types across modules · 11 · Sequenced collections · 12 · Generational ZGC · 13 · Foreign Function & Memory API · 14 · Vector API · 15 · Java 21 features that changed later

## Run it

Requires JDK 21+.

```bash
mvn test                       # everything
mvn test -Dtest=PinningTest    # one post
```

## About

Written by [Venkata Sai Dasari](https://venkatasaidasari.vercel.app), a senior backend engineer working on Java, Spring Boot, Kafka and AWS. Corrections and questions are welcome as issues.
