import { Consolidation, PeriodEntry } from '../../api';
import { consolidatedMessage, manualPolicyText, noChangesText, RULE_LABELS } from './consolidation-text';

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
