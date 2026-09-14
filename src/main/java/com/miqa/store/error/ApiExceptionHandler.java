package com.miqa.store.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(CatalogNotFoundException.class)
    public ResponseEntity<ApiError> notFound(CatalogNotFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(404).body(error(404, request.getRequestURI()));
    }
    @Override protected ResponseEntity<Object> handleMethodArgumentNotValid(org.springframework.web.bind.MethodArgumentNotValidException ex, HttpHeaders headers,HttpStatusCode status,WebRequest request){
        var fields=new java.util.LinkedHashMap<String,String>();
        ex.getBindingResult().getFieldErrors().forEach(e->fields.putIfAbsent(e.getField(),e.getDefaultMessage()==null?"Valor invalido":e.getDefaultMessage()));
        String path=((ServletWebRequest)request).getRequest().getRequestURI();
        return ResponseEntity.badRequest().body(new ApiError(Instant.now(),400,"INVALID_REQUEST","Revisa los campos: "+String.join(", ",fields.keySet()),path,fields));
    }
    @Override protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        return new ResponseEntity<>(error(status.value(), path), headers, status);
    }
    @ExceptionHandler(com.miqa.store.admin.AdminFailure.class)
    public ResponseEntity<ApiError> admin(com.miqa.store.admin.AdminFailure ex,HttpServletRequest request){
        String code=switch(ex.status()){case 400->"INVALID_REQUEST";case 401->"UNAUTHENTICATED";case 404->"NOT_FOUND";case 409->"CONFLICT";case 429->"TOO_MANY_REQUESTS";default->"REQUEST_ERROR";};
        return ResponseEntity.status(ex.status()).body(new ApiError(Instant.now(),ex.status(),code,ex.getMessage(),request.getRequestURI(),Map.of()));
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> conflict(Exception ex,HttpServletRequest request){return ResponseEntity.status(409).body(new ApiError(Instant.now(),409,"CONFLICT","Ya existe un registro con ese slug o nombre; revisa los datos",request.getRequestURI(),Map.of()));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        // Exception messages/causes may contain SQL values or authentication input.
        log.error("Unexpected request failure; exception type: {}", exception.getClass().getName());
        return ResponseEntity.status(500).body(error(500, request.getRequestURI()));
    }
    private ApiError error(int status, String path) {
        String code = switch (status) { case 400 -> "INVALID_REQUEST"; case 404 -> "NOT_FOUND"; case 405 -> "METHOD_NOT_ALLOWED"; default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_ERROR"; };
        String message = switch (status) { case 400 -> "Parámetros de solicitud inválidos"; case 404 -> "Recurso no disponible"; case 405 -> "Método no permitido"; default -> status >= 500 ? "No se pudo completar la solicitud" : "Solicitud no permitida"; };
        return new ApiError(Instant.now(), status, code, message, path,
                status == 400 ? Map.of("request", "Revisa formato y longitud de los parámetros") : Map.of());
    }
}
