package com.miqa.store.erp;

import com.miqa.store.admin.AdminFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static com.miqa.store.erp.ErpCatalogDtos.*;

@Service
public class ErpCatalogService {
    private final ErpCatalogClient client;
    private final ErpCatalogRepository repository;
    private final TransactionTemplate write;

    @Autowired
    public ErpCatalogService(ErpCatalogClient client, ErpCatalogRepository repository, DataSource dataSource) {
        this(client, repository, new JdbcTransactionManager(dataSource));
    }
    ErpCatalogService(ErpCatalogClient client, ErpCatalogRepository repository, PlatformTransactionManager transactions) {
        this.client = client;
        this.repository = repository;
        write = new TransactionTemplate(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write.setTimeout(45);
    }

    public SyncStatus synchronize() {
        return Objects.requireNonNull(write.execute(transaction -> {
            if (!repository.trySyncLock()) throw new AdminFailure(409, "Ya hay una sincronizacion en curso");
            List<ErpCatalogContract> services;
            try {
                services = client.fetchAvailable();
                ErpCatalogValidation.listing(services);
            } catch (ErpCatalogFailure ex) {
                repository.failure(Instant.now(), ex.code());
                return repository.status();
            }
            Instant now = Instant.now();
            var previous = new HashMap<>(repository.revisions());
            int changed = 0;
            for (var service : services) {
                if (service.catalogRevision().equals(previous.remove(service.erpServiceId()))) {
                    repository.seen(service.erpServiceId(), now);
                } else {
                    repository.upsert(service, now);
                    changed++;
                }
            }
            int missing = 0;
            for (String id : previous.keySet()) missing += repository.missing(id, now);
            repository.success(now, services.size(), changed, missing);
            return repository.status();
        }));
    }

    public SyncStatus status() { return repository.status(); }
    public List<Projection> projections() { return repository.projections(); }
    public ProductErpBinding binding(String productId) {
        return repository.binding(productId).orElseThrow(() -> new AdminFailure(404, "Vinculo no encontrado"));
    }
    public ProductErpBinding bind(String productId, BindingInput input) {
        if (input == null || !ErpCatalogValidation.id(input.erpServiceId()) || input.active() == null)
            throw new AdminFailure(400, "Vinculo invalido");
        return Objects.requireNonNull(write.execute(transaction -> {
            if (!repository.lockProduct(productId)) throw new AdminFailure(404, "Producto no encontrado");
            if (!repository.serviceExists(input.erpServiceId())) throw new AdminFailure(404, "Servicio ERP no sincronizado");
            repository.bind(productId, input);
            return binding(productId);
        }));
    }
}
