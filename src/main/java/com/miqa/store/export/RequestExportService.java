package com.miqa.store.export;

import com.miqa.store.quote.QuoteSnapshot;
import com.miqa.store.quote.QuoteV2Dtos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static com.miqa.store.export.RequestExportDtos.*;

@Service
public class RequestExportService {
    private final RequestExportRepository repository;
    private final TransactionTemplate read;
    // Deserialize into known records, never export arbitrary persisted JSON properties.
    private final JsonMapper mapper=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @Autowired
    public RequestExportService(RequestExportRepository repository, DataSource dataSource) {
        this(repository,new JdbcTransactionManager(dataSource));
    }
    RequestExportService(RequestExportRepository repository, PlatformTransactionManager transactions) {
        this.repository=repository;
        read=new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setTimeout(10);
    }
    public Page list(String cursor, String from, String before, Integer limit) {
        if(cursor!=null && (from!=null || before!=null || limit!=null)) throw RequestExportFailure.invalid();
        // Validate untrusted input before starting any database work.
        RequestExportCursor decoded=cursor==null ? null : RequestExportCursor.decode(cursor);
        Instant lower=from==null ? Instant.EPOCH : instant(from);
        Instant upper=before==null ? null : instant(before);
        int size=limit==null ? 50 : limit;
        if(size<1 || size>100) throw RequestExportFailure.invalid();
        return Objects.requireNonNull(read.execute(tx -> {
            var query=decoded==null ? new RequestExportCursor(lower,upper==null ? repository.now() : upper,null,null,size) : decoded;
            var fetched=repository.list(query);
            boolean more=fetched.size()>query.limit();
            var rows=List.copyOf(fetched.subList(0,Math.min(fetched.size(),query.limit())));
            String next=null;
            if(more) {
                var last=rows.getLast();
                next=new RequestExportCursor(query.from(),query.before(),last.createdAt(),last.id(),query.limit()).encode();
            }
            return new Page(1,"MIQA_STORE",new Window(query.from(),query.before()),rows,next,more);
        }));
    }
    public Detail detail(String id) {
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw RequestExportFailure.invalid();
        return Objects.requireNonNull(read.execute(tx -> {
            var header=repository.header(id).orElseThrow(() -> new RequestExportFailure(404,"NOT_FOUND"));
            var items=repository.items(id).stream().map(this::item).toList();
            if(items.isEmpty()) throw new RequestExportFailure(409,"INVALID_HISTORICAL_SNAPSHOT");
            var summary=header.summary();
            return new Detail(1,"MIQA_STORE",summary.id(),summary.reference(),summary.origin(),summary.status(),
                    summary.createdAt(),summary.updatedAt(),header.contact(),header.notes(),items);
        }));
    }
    Item item(RequestExportRepository.StoredItem stored) {
        try {
            var json=mapper.readTree(stored.snapshot());
            var version=json.get("schemaVersion");
            if(version==null || !version.isIntegralNumber()) throw new IllegalArgumentException();
            if(version.intValue()==1) {
                var snapshot=mapper.treeToValue(json,QuoteSnapshot.class);
                if(!Objects.equals(stored.productId(),snapshot.productId())) throw new IllegalArgumentException();
                return new Item(stored.id(),stored.position(),1,"LEGACY",snapshot,null);
            }
            if(version.intValue()==2) {
                var snapshot=mapper.treeToValue(json,QuoteV2Dtos.ErpSnapshot.class);
                if(!Objects.equals(stored.productId(),snapshot.productId()) || snapshot.erpServiceId()==null) throw new IllegalArgumentException();
                return new Item(stored.id(),stored.position(),2,"ERP",null,snapshot);
            }
            throw new IllegalArgumentException();
        } catch(RuntimeException ex) { throw new RequestExportFailure(409,"INVALID_HISTORICAL_SNAPSHOT"); }
    }
    private static Instant instant(String value) {
        try { if(value.length()>40) throw RequestExportFailure.invalid(); return Instant.parse(value); }
        catch(RuntimeException ex) { throw RequestExportFailure.invalid(); }
    }
}
