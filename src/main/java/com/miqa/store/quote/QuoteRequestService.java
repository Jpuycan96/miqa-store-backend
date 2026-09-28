package com.miqa.store.quote;

import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.Objects;
import static com.miqa.store.quote.QuoteRequestDtos.*;

@Service
public class QuoteRequestService {
    private final QuoteRequestRepository repository;
    private final QuoteCatalog catalog;
    private final QuoteRequestCanonicalizer canonicalizer;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    @Autowired
    public QuoteRequestService(QuoteRequestRepository repository, QuoteCatalog catalog,
                               QuoteRequestCanonicalizer canonicalizer, DataSource dataSource) {
        // JDBC-only boundary: do not replace the application's JPA transaction manager.
        this(repository, catalog, canonicalizer, new JdbcTransactionManager(dataSource));
    }

    QuoteRequestService(QuoteRequestRepository repository, QuoteCatalog catalog,
                        QuoteRequestCanonicalizer canonicalizer, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.catalog = catalog;
        this.canonicalizer = canonicalizer;
        write = new TransactionTemplate(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Every catalog row/option in a request comes from the same database snapshot.
        write.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write.setTimeout(15);
        read = new TransactionTemplate(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        read.setTimeout(10);
    }

    public Result submit(String key, Submission input) {
        var canonical = canonicalizer.canonicalize(key, input);
        var previous = read.execute(status -> repository.find(canonical.key()));
        if (previous != null && previous.isPresent()) return replay(previous.get(), canonical.hash());
        try {
            return Objects.requireNonNull(write.execute(status -> {
                var existing = repository.find(canonical.key());
                if (existing.isPresent()) return replay(existing.get(), canonical.hash());
                var snapshots = canonical.submission().items().stream().map(catalog::snapshot).toList();
                return new Result(repository.insert(canonical, snapshots).confirmation(), false);
            }));
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyCollision(exception)) throw exception;
            // The losing INSERT has rolled back. Recover in a new transaction, without catalog validation.
            var winner = read.execute(status -> repository.find(canonical.key()));
            if (winner == null || winner.isEmpty()) throw exception;
            return replay(winner.get(), canonical.hash());
        }
    }

    private Result replay(QuoteRequestRepository.Receipt receipt, String hash) {
        if (!receipt.hash().equals(hash)) throw new QuoteRequestFailure(409, "Idempotency-Key ya fue usada con otro contenido");
        return new Result(receipt.confirmation(), true);
    }

    static boolean isIdempotencyCollision(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres && "23505".equals(postgres.getSQLState())
                    && postgres.getServerErrorMessage() != null
                    && QuoteRequestRepository.IDEMPOTENCY_CONSTRAINT.equals(postgres.getServerErrorMessage().getConstraint())) return true;
        }
        return false;
    }
}
