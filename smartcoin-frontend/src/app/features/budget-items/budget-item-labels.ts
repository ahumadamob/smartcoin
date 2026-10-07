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
