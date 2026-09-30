package io.github.guilhermebars.matchforge.risk;

import io.github.guilhermebars.matchforge.domain.AccountId;
import io.github.guilhermebars.matchforge.domain.Asset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Checked balances. Aggregate customer holdings per asset are bounded by Long.MAX_VALUE. */
public final class AccountBook {
    public record Balance(long available, long reserved) {
        public Balance {
            if (available < 0 || reserved < 0) throw new IllegalArgumentException("negative balance");
            Math.addExact(available, reserved);
        }

        public long total() {
            return Math.addExact(available, reserved);
        }
    }

    public record AssetBalance(Asset asset, Balance balance) {}

    public record Account(AccountId accountId, List<AssetBalance> balances) {
        public Account {
            balances = List.copyOf(balances);
        }
    }

    private final Map<AccountId, Map<Asset, Balance>> accounts = new LinkedHashMap<>();

    public boolean contains(AccountId id) {
        return accounts.containsKey(id);
    }

    public void create(AccountId id) {
        if (accounts.putIfAbsent(Objects.requireNonNull(id), new LinkedHashMap<>()) != null)
            throw new IllegalArgumentException("account exists");
    }

    public Balance balance(AccountId id, Asset asset) {
        var balances = accounts.get(id);
        if (balances == null) throw new IllegalArgumentException("unknown account");
        return balances.getOrDefault(asset, new Balance(0, 0));
    }

    public void change(AccountId id, Asset asset, long availableDelta, long reservedDelta) {
        var old = balance(id, asset);
        var next = new Balance(
                Math.addExact(old.available(), availableDelta), Math.addExact(old.reserved(), reservedDelta));
        accounts.get(id).put(asset, next);
    }

    public List<Account> snapshot() {
        return accounts.entrySet().stream()
                .map(a -> new Account(
                        a.getKey(),
                        a.getValue().entrySet().stream()
                                .map(b -> new AssetBalance(b.getKey(), b.getValue()))
                                .toList()))
                .toList();
    }

    public static AccountBook restore(List<Account> snapshot) {
        var result = new AccountBook();
        for (var a : snapshot) {
            result.create(a.accountId());
            for (var b : a.balances()) {
                if (result.accounts.get(a.accountId()).putIfAbsent(b.asset(), b.balance()) != null)
                    throw new IllegalArgumentException("duplicate balance");
            }
        }
        return result;
    }

    public Map<Asset, Long> totals() {
        var totals = new LinkedHashMap<Asset, Long>();
        accounts.values().forEach(a -> a.forEach((asset, b) -> totals.merge(asset, b.total(), Math::addExact)));
        return Collections.unmodifiableMap(totals);
    }
}
