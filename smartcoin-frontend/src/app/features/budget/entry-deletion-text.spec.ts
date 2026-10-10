import { DeletionPlan } from '../../api';
import { confirmLabel, onlyThisText, originNote, successMessage, thisAndFutureText } from './entry-deletion-text';

const MONTHS: Record<string, string> = {
  '2026-10': 'octubre 2026',
  '2026-11': 'noviembre 2026',
  '2026-12': 'diciembre 2026',
  '2027-02': 'febrero 2027',
};
const month = (period: string) => MONTHS[period] ?? period;

const plan = (overrides: Partial<DeletionPlan>): DeletionPlan => ({
  allowed: true,
  entryCount: 1,
  fromPeriod: '2026-11',
  toPeriod: '2026-11',
  itemOutcome: 'KEEPS_ITEM',
  newEndPeriod: null,
  blockers: [],
  ...overrides,
});

describe('textos de la eliminación (HU-18)', () => {
  describe('«Solo este mes»', () => {
    it('elimina solo esa partida y el Concepto sigue igual', () => {
      expect(onlyThisText(plan({}), 'Luz', month)).toBe(
        'Se elimina solo la partida de noviembre 2026. El Concepto «Luz» sigue igual y esta partida no vuelve a aparecer.',
      );
    });

    it('si es la última, avisa que el Concepto también se elimina', () => {
      const text = onlyThisText(plan({ itemOutcome: 'REMOVES_ITEM' }), 'Heladera', month);

      expect(text).toContain('Se elimina solo la partida de noviembre 2026.');
      expect(text).toContain('única partida que le queda al Concepto «Heladera»');
      expect(text).toContain('el Concepto también se elimina');
    });
  });

  describe('«Este mes y los siguientes»', () => {
    it('cuenta las partidas, dice desde qué mes y cuándo termina el Concepto', () => {
      const text = thisAndFutureText(
        plan({ entryCount: 3, fromPeriod: '2026-12', toPeriod: '2027-02', itemOutcome: 'ENDS_ITEM', newEndPeriod: '2026-11' }),
        'Luz',
        month,
      );

      expect(text).toBe(
        'Se eliminan las 3 partidas de «Luz», de diciembre 2026 a febrero 2027. El Concepto «Luz» termina en noviembre 2026 y no genera más partidas; las anteriores no cambian.',
      );
    });

    it('con una sola partida lo dice en singular', () => {
      const text = thisAndFutureText(
        plan({ entryCount: 1, fromPeriod: '2027-02', toPeriod: '2027-02', itemOutcome: 'ENDS_ITEM', newEndPeriod: '2026-12' }),
        'Luz',
        month,
      );

      expect(text).toContain('Se elimina la partida de febrero 2027.');
      expect(text).toContain('termina en diciembre 2026');
    });

    it('si no queda ninguna partida, el Concepto desaparece', () => {
      const text = thisAndFutureText(
        plan({ entryCount: 5, fromPeriod: '2026-10', toPeriod: '2027-02', itemOutcome: 'REMOVES_ITEM' }),
        'Luz',
        month,
      );

      expect(text).toContain('Se eliminan las 5 partidas de «Luz», de octubre 2026 a febrero 2027.');
      expect(text).toContain('No queda ninguna partida, así que el Concepto «Luz» también se elimina.');
      expect(text).not.toContain('termina en');
    });
  });

  describe('el botón de confirmar', () => {
    it('dice lo que va a hacer', () => {
      expect(confirmLabel('ONLY_THIS', true)).toBe('Eliminar solo este mes');
      expect(confirmLabel('THIS_AND_FUTURE', true)).toBe('Eliminar este mes y los siguientes');
      expect(confirmLabel(null, true)).toBe('Eliminar');
      expect(confirmLabel(null, false)).toBe('Eliminar partida');
    });
  });

  describe('avisos según el origen de una partida sin Concepto', () => {
    it('el saldo postergado y la diferencia de cierre dicen qué significa eliminarlas', () => {
      expect(originNote('CARRIED_OVER')).toContain('saldo que quedó pendiente');
      expect(originNote('CARRIED_OVER')).toContain('deja de figurar en tu presupuesto');
      expect(originNote('CLOSING_DIFFERENCE')).toContain('diferencia que encontró el cierre');
      expect(originNote('CLOSING_DIFFERENCE')).toContain('vuelve a aparecer al cerrar el mes');
    });

    it('una puntual no tiene aviso', () => {
      expect(originNote('ONE_OFF')).toBeNull();
    });
  });

  describe('aviso de lo que se hizo', () => {
    it('partida sin Concepto', () => {
      expect(successMessage(plan({ itemOutcome: null }), null, 'Service del auto', month)).toBe(
        'Partida «Service del auto» eliminada.',
      );
    });

    it('solo este mes, con y sin desaparición del Concepto', () => {
      expect(successMessage(plan({}), 'ONLY_THIS', 'Luz', month)).toBe('Se eliminó «Luz» de noviembre 2026.');
      expect(successMessage(plan({ itemOutcome: 'REMOVES_ITEM' }), 'ONLY_THIS', 'Luz', month)).toBe(
        'Se eliminó «Luz» de noviembre 2026 y el Concepto, que no tenía más partidas.',
      );
    });

    it('este mes y los siguientes: cuántas se eliminaron y cuándo termina el Concepto', () => {
      const ends = plan({ entryCount: 3, itemOutcome: 'ENDS_ITEM', newEndPeriod: '2026-10' });
      expect(successMessage(ends, 'THIS_AND_FUTURE', 'Luz', month)).toBe(
        'Se eliminaron 3 partidas de «Luz». El Concepto termina en octubre 2026.',
      );
      expect(successMessage(plan({ entryCount: 1, itemOutcome: 'ENDS_ITEM', newEndPeriod: '2026-10' }), 'THIS_AND_FUTURE', 'Luz', month)).toBe(
        'Se eliminó la partida de «Luz» de noviembre 2026. El Concepto termina en octubre 2026.',
      );
      expect(successMessage(plan({ entryCount: 4, itemOutcome: 'REMOVES_ITEM' }), 'THIS_AND_FUTURE', 'Luz', month)).toBe(
        'Se eliminaron 4 partidas de «Luz» y el Concepto, que no tenía más partidas.',
      );
    });
  });
});
