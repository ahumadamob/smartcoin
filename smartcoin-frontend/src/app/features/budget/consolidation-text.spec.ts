import { Consolidation, EstimationBasis, PeriodEntry } from '../../api';
import { averageBasisText, consolidatedMessage, manualPolicyText, noChangesText, RULE_LABELS } from './consolidation-text';

const money = (amount: number) => `$ ${amount.toFixed(2).replace('.', ',')}`;

const consolidated = (estimate: number | null, updatedEntries: number): Consolidation => ({
  entry: { name: 'Sueldo A', currency: 'ARS', forecastAmount: 1850000 } as PeriodEntry,
  estimate,
  updatedEntries,
});

describe('textos de la consolidación (HU-23)', () => {
  it('las reglas de estimación se muestran con el vocabulario del glosario', () => {
    expect(RULE_LABELS).toEqual({ LAST_VALUE: 'Último valor', AVERAGE_LAST_3: 'Promedio de los últimos 3' });
  });

  it('el aviso dice el monto consolidado y cuántas partidas cambiaron', () => {
    expect(consolidatedMessage(consolidated(1850000, 24), money)).toBe(
      '«Sueldo A» consolidada por $ 1850000,00. Se actualizaron 24 partidas de los meses siguientes.',
    );
    expect(consolidatedMessage(consolidated(1850000, 1), money)).toContain('Se actualizaron 1 partida de los meses siguientes');
  });

  it('el aviso de una partida sin Concepto no habla de otros meses', () => {
    expect(consolidatedMessage(consolidated(null, 0), money)).toBe('«Sueldo A» consolidada por $ 1850000,00.');
  });

  it('si no cambió ningún mes siguiente, lo dice', () => {
    expect(consolidatedMessage(consolidated(1850000, 0), money)).toBe(
      '«Sueldo A» consolidada por $ 1850000,00. No cambió ningún mes siguiente.',
    );
  });

  it('sin cambios distingue «no hay partidas» de «ya tienen ese valor»', () => {
    expect(noChangesText(0)).toContain('No hay partidas pendientes');
    expect(noChangesText(3)).toContain('ya tienen ese valor');
  });

  it('con editadas explica que no se puede consolidar todavía', () => {
    expect(manualPolicyText(1)).toContain('Hay 1 partida editada a mano');
    expect(manualPolicyText(2)).toContain('Hay 2 partidas editadas a mano');
    expect(manualPolicyText(2)).toContain('no se puede consolidar esta partida por ahora');
  });
});

describe('texto del promedio (HU-24)', () => {
  const names: Record<string, string> = {
    '2026-08': 'agosto 2026',
    '2026-09': 'septiembre 2026',
    '2026-10': 'octubre 2026',
    '2026-11': 'noviembre 2026',
    '2026-12': 'diciembre 2026',
    '2027-01': 'enero 2027',
  };
  const format = (period: string) => names[period];
  const basis = (...periods: string[]): EstimationBasis[] => periods.map((period) => ({ period, amount: 100 }));

  it('con 3 consolidaciones nombra los tres meses, del más antiguo al más reciente', () => {
    // el backend los informa del más reciente al más antiguo
    expect(averageBasisText(basis('2026-10', '2026-09', '2026-08'), format)).toBe(
      'Promedio de agosto, septiembre y octubre.',
    );
  });

  it('con 2 consolidaciones aclara que promedia las que hay', () => {
    expect(averageBasisText(basis('2026-10', '2026-09'), format)).toBe(
      'Promedio de septiembre y octubre: las únicas consolidadas hasta ahora. ' +
        'Se promedian las que hay y, con más consolidaciones, las 3 más recientes.',
    );
  });

  it('con 1 consolidación aclara que promedia la que hay', () => {
    expect(averageBasisText(basis('2026-10'), format)).toBe(
      'Promedio de octubre: la única consolidada hasta ahora. ' +
        'Se promedian las que hay y, con más consolidaciones, las 3 más recientes.',
    );
  });

  it('si los meses son de años distintos, nombra el año', () => {
    expect(averageBasisText(basis('2027-01', '2026-12', '2026-11'), format)).toBe(
      'Promedio de noviembre 2026, diciembre 2026 y enero 2027.',
    );
  });

  it('sin consolidaciones no dice nada', () => {
    expect(averageBasisText([], format)).toBe('');
  });
});
