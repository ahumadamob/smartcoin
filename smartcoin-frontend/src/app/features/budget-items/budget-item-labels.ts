import { BudgetItemRequest } from '../../api';

/** Textos en pantalla de los tipos y valores de un Concepto (docs/glosario.md). */
export const KIND_LABELS: Record<BudgetItemRequest.KindEnum, string> = {
  INCOME: 'Ingreso',
  EXPENSE: 'Gasto',
};

export const KINDS = Object.keys(KIND_LABELS) as BudgetItemRequest.KindEnum[];

export const PERIODICITY_LABELS: Record<BudgetItemRequest.PeriodicityEnum, string> = {
  MONTHLY: 'Mensual',
  BIMONTHLY: 'Bimestral',
  QUARTERLY: 'Trimestral',
  SEMIANNUAL: 'Semestral',
  ANNUAL: 'Anual',
};

export const PERIODICITIES = Object.keys(PERIODICITY_LABELS) as BudgetItemRequest.PeriodicityEnum[];

/** Cada regla de estimación con la línea que la explica (HU-10, criterio 5). */
export const ESTIMATION_RULES: {
  value: BudgetItemRequest.EstimationRuleEnum;
  label: string;
  description: string;
}[] = [
  {
    value: 'LAST_VALUE',
    label: 'Último valor',
    description:
      'Al consolidar una partida, las siguientes toman su monto real. Para sueldos y gastos que cambian poco.',
  },
  {
    value: 'AVERAGE_LAST_3',
    label: 'Promedio de los últimos 3',
    description:
      'Al consolidar una partida, las siguientes toman el promedio de las últimas 3 consolidadas. Para servicios que varían según el consumo.',
  },
];

/** Texto de la opción de desfase de mes (HU-10, criterio 5). */
export const OFFSET_LABEL =
  'Vence el mes anterior al período, por ejemplo un sueldo que se cobra a fin del mes anterior';

/** Vencimiento en palabras: "día 25" o "día 25, el mes anterior" (el desfase de mes, glosario). */
export function dueText(dueDay: number, dueMonthOffset: number): string {
  return dueMonthOffset < 0 ? `día ${dueDay}, el mes anterior` : `día ${dueDay}`;
}

/** Lo que hace falta de un Concepto de la lista para decir su estado. */
export interface StatusSource {
  status: 'ACTIVE' | 'FINISHED' | 'SCHEDULED';
  currentInstallment?: number | null;
  installmentsRemaining?: number | null;
  installmentsTotal?: number | null;
}

/**
 * Texto del estado (D-27): "Cuota 4 de 12, quedan 8", "Cuota 12 de 12, última", "Activo", "Finalizado" o
 * "Por comenzar". Los números los calcula el backend.
 */
export function statusText(item: StatusSource): string {
  switch (item.status) {
    case 'FINISHED':
      return 'Finalizado';
    case 'SCHEDULED':
      return 'Por comenzar';
    case 'ACTIVE': {
      const { currentInstallment: current, installmentsRemaining: remaining, installmentsTotal: total } = item;
      if (current == null || remaining == null || total == null) {
        return 'Activo';
      }
      const tail = remaining === 0 ? 'última' : remaining === 1 ? 'queda 1' : `quedan ${remaining}`;
      return `Cuota ${current} de ${total}, ${tail}`;
    }
  }
}
