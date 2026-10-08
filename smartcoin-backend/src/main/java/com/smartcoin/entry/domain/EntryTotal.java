package com.smartcoin.entry.domain;

import java.math.BigDecimal;

/** Suma de los movimientos de una partida, tal como la devuelve la consulta agrupada por partida (RN-17). */
public record EntryTotal(Long entryId, BigDecimal total) {
}
