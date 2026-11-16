package io.github.theonlyrafael.sagastudy.orchestrated.order.web;

import io.github.theonlyrafael.sagastudy.orchestrated.order.domain.*;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class OrderErrorHandler {
    public record Error(String codigo, String mensagem) {}

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Error> conflict() {
        return error(409, "CONFLITO_IDEMPOTENCIA", "Chave usada com dados diferentes");
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Error> missing() {
        return error(404, "ORDEM_NAO_ENCONTRADA", "Ordem não encontrada");
    }

    @ExceptionHandler(AssetNotFoundException.class)
    public ResponseEntity<Error> asset() {
        return error(422, "ATIVO_NAO_ENCONTRADO", "Ativo não cadastrado");
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Error> invalid() {
        return error(400, "ENTRADA_INVALIDA", "Verifique os campos e a chave da requisição");
    }

    private ResponseEntity<Error> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(new Error(code, message));
    }
}
