package io.github.guilhermebars.matchforge.ledger;

import io.github.guilhermebars.matchforge.domain.*;
import io.github.guilhermebars.matchforge.risk.AccountBook;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Signed postings: positive credits holdings, negative debits holdings. Fees are zero. */
public final class Ledger {
    public static final AccountId EXTERNAL = new AccountId("EXTERNAL");
    public record Posting(AccountId accountId, Asset asset, long amount) {}
    public record Transaction(long sequence, Instant timestamp, String reference, List<Posting> postings) {
        public Transaction {
            postings = List.copyOf(postings);
            if (postings.isEmpty() || trialBalance(postings).values().stream().anyMatch(v -> v.signum() != 0))
                throw new IllegalArgumentException("unbalanced transaction");
        }
    }
    private final List<Transaction> transactions = new ArrayList<>();
    public void append(Transaction transaction) { transactions.add(Objects.requireNonNull(transaction)); }
    public List<Transaction> transactions() { return List.copyOf(transactions); }
    public static Map<Asset, BigInteger> trialBalance(List<Posting> postings) {
        var totals = new LinkedHashMap<Asset, BigInteger>();
        postings.forEach(p -> totals.merge(p.asset(), BigInteger.valueOf(p.amount()), BigInteger::add));
        return Collections.unmodifiableMap(totals);
    }
    public Map<Asset, BigInteger> trialBalance() {
        return trialBalance(transactions.stream().flatMap(t -> t.postings().stream()).toList());
    }
    /** Also reconciles each customer's holdings, not just the global sum. */
    public void assertConservation(List<AccountBook.Account> accounts) {
        record Key(AccountId account, Asset asset) {}
        var expected = new HashMap<Key, BigInteger>();
        for (var t : transactions) for (var p : t.postings()) {
            if (!p.accountId().equals(EXTERNAL))
                expected.merge(new Key(p.accountId(), p.asset()), BigInteger.valueOf(p.amount()), BigInteger::add);
        }
        for (var a : accounts) for (var b : a.balances()) {
            var key = new Key(a.accountId(), b.asset());
            if (!expected.getOrDefault(key, BigInteger.ZERO).equals(BigInteger.valueOf(b.balance().total())))
                throw new IllegalStateException("ledger/balance mismatch: " + key);
            expected.remove(key);
        }
        if (expected.values().stream().anyMatch(v -> v.signum() != 0))
            throw new IllegalStateException("unreconciled ledger holdings");
        if (trialBalance().values().stream().anyMatch(v -> v.signum() != 0))
            throw new IllegalStateException("unbalanced ledger");
    }
}
