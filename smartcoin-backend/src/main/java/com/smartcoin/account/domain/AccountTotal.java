package com.smartcoin.account.domain;

import java.math.BigDecimal;

/** Total de una suma agrupada por cuenta, tal como lo devuelven las consultas de saldo (RN-35). */
public record AccountTotal(Long accountId, BigDecimal total) {
}
