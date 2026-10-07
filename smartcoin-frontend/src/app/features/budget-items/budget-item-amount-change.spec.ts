import { amountChangeMessage } from './budget-item-amount-change';

describe('amountChangeMessage', () => {
  it('dice el cambio, cuántas partidas reemplaza y que las editadas no cambian', () => {
    expect(amountChangeMessage('$ 85.000,00', '$ 90.000,50', { pendingNotManual: 23, pendingManual: 2 })).toBe(
      'Monto vigente: de $ 85.000,00 a $ 90.000,50. ' +
        'Se reemplazará el monto presupuestado de 23 partidas pendientes sin editar de los períodos abiertos. ' +
        'Las 2 partidas editadas a mano no cambian. ' +
        'Las consolidadas y las de períodos cerrados tampoco cambian.',
    );
  });

  it('con una sola partida de cada tipo lo dice en singular', () => {
    const message = amountChangeMessage('$ 1,00', '$ 2,00', { pendingNotManual: 1, pendingManual: 1 });

    expect(message).toContain('de 1 partida pendiente sin editar');
    expect(message).toContain('La partida editada a mano no cambia.');
  });

  it('sin partidas para reemplazar avisa que el monto se usará en las que se generen', () => {
    const message = amountChangeMessage('$ 1,00', '$ 2,00', { pendingNotManual: 0, pendingManual: 3 });

    expect(message).toContain('Hoy no hay partidas pendientes sin editar en los períodos abiertos');
    expect(message).toContain('se generen de ahora en adelante');
    expect(message).not.toContain('Se reemplazará');
    expect(message).toContain('Las 3 partidas editadas a mano no cambian.');
  });

  it('sin partidas editadas igual avisa que las editadas no cambian', () => {
    const message = amountChangeMessage('$ 1,00', '$ 2,00', { pendingNotManual: 5, pendingManual: 0 });

    expect(message).toContain('Las partidas editadas a mano no cambian (hoy no hay ninguna).');
  });
});
