package com.miqa.store.export;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** A position in a creation-time window, NOT a commit watermark or a database snapshot. */
record RequestExportCursor(Instant from, Instant before, Instant afterTime, String afterId, int limit) {
    RequestExportCursor {
        if (from==null || before==null || from.isBefore(Instant.EPOCH) || !from.isBefore(before)
                || before.isAfter(Instant.parse("9999-12-31T23:59:59Z")) || limit<1 || limit>100
                || from.getNano()%1000!=0 || before.getNano()%1000!=0
                || (afterTime==null)!=(afterId==null)) throw RequestExportFailure.invalid();
        if (afterTime!=null && (afterTime.isBefore(from) || !afterTime.isBefore(before)
                || afterTime.getNano()%1000!=0 || !afterId.matches("[A-Za-z0-9_-]{1,64}"))) throw RequestExportFailure.invalid();
    }
    String encode() {
        if(afterTime==null) throw RequestExportFailure.invalid();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("1|"+limit+"|"+from+"|"+before+"|"+afterTime+"|"+afterId).getBytes(StandardCharsets.US_ASCII));
    }
    static RequestExportCursor decode(String token) {
        try {
            if(token==null || token.length()>512 || !token.matches("[A-Za-z0-9_-]+")) throw RequestExportFailure.invalid();
            String[] parts=new String(Base64.getUrlDecoder().decode(token),StandardCharsets.US_ASCII).split("\\|",-1);
            if(parts.length!=6 || !parts[0].equals("1")) throw RequestExportFailure.invalid();
            var value=new RequestExportCursor(Instant.parse(parts[2]),Instant.parse(parts[3]),Instant.parse(parts[4]),parts[5],Integer.parseInt(parts[1]));
            if(!value.encode().equals(token)) throw RequestExportFailure.invalid();
            return value;
        } catch(RuntimeException ex) { throw RequestExportFailure.invalid(); }
    }
}
