import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DialogService, DynamicDialogRef } from 'primeng/dynamicdialog';
import { Dialogs } from './dialogs';

@Component({ template: '' })
class Content {}

describe('Dialogs', () => {
  let refs: DynamicDialogRef[];
  let service: { open: ReturnType<typeof vi.fn> };
  let trigger: HTMLButtonElement;

  beforeEach(() => {
    refs = [];
    // El `DialogService` de PrimeNG se simula: cada `open` devuelve una referencia real, que el test cierra y destruye
    // como lo haría el diálogo (primero `close`, después de la animación `destroy`).
    service = {
      open: vi.fn(() => {
        const ref = new DynamicDialogRef();
        refs.push(ref);
        return ref;
      }),
    };
    TestBed.configureTestingModule({ providers: [{ provide: DialogService, useValue: service }] });
    trigger = document.createElement('button');
    document.body.appendChild(trigger);
    trigger.focus();
  });

  afterEach(() => trigger.remove());

  const escape = () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }));
  const finish = (ref: DynamicDialogRef, result?: unknown) => {
    ref.close(result);
    ref.destroy();
  };

  it('abre un diálogo modal, sin el encabezado de PrimeNG, con los datos y el ancho pedidos', () => {
    TestBed.inject(Dialogs).open(Content, { id: 7 }, { width: '640px' });

    expect(service.open).toHaveBeenCalledWith(
      Content,
      expect.objectContaining({ data: { id: 7 }, width: '640px', modal: true, showHeader: false, focusTrap: true }),
    );
  });

  it('emite el resultado recién cuando el diálogo se destruyó, como `afterClosed` de Material', () => {
    const results: unknown[] = [];
    TestBed.inject(Dialogs).open(Content, {}, { width: '480px' }).subscribe((r) => results.push(r));

    refs[0].close({ ok: true });
    expect(results).toEqual([]);

    refs[0].destroy();
    expect(results).toEqual([{ ok: true }]);
  });

  it('al cerrarse devuelve el foco al elemento que lo abrió', () => {
    TestBed.inject(Dialogs).open(Content, {}, { width: '480px' }).subscribe();
    (document.activeElement as HTMLElement).blur();

    finish(refs[0]);

    expect(document.activeElement).toBe(trigger);
  });

  it('no le saca el foco a quien ya lo movió, ni lo devuelve a un elemento que ya no está', () => {
    const other = document.createElement('button');
    document.body.appendChild(other);
    TestBed.inject(Dialogs).open(Content, {}, { width: '480px' }).subscribe();
    other.focus();
    finish(refs[0]);
    expect(document.activeElement).toBe(other);

    trigger.focus();
    TestBed.inject(Dialogs).open(Content, {}, { width: '480px' }).subscribe();
    trigger.remove();
    (document.activeElement as HTMLElement).blur();
    finish(refs[1]);
    expect(document.activeElement).toBe(document.body);
    other.remove();
  });

  it('Escape cierra sin resultado solo el de arriba; con otro debajo, el siguiente Escape cierra ese', () => {
    const dialogs = TestBed.inject(Dialogs);
    const first: unknown[] = [];
    const second: unknown[] = [];
    dialogs.open(Content, {}, { width: '640px' }).subscribe((r) => first.push(r));
    dialogs.open(Content, {}, { width: '480px' }).subscribe((r) => second.push(r));
    const closeFirst = vi.spyOn(refs[0], 'close');

    escape();
    refs[1].destroy();
    expect(second).toEqual([undefined]);
    expect(closeFirst).not.toHaveBeenCalled();

    escape();
    refs[0].destroy();
    expect(first).toEqual([undefined]);
  });

  it('un Escape que ya consumió otro (la lista abierta de un `p-select`) no cierra el diálogo', () => {
    TestBed.inject(Dialogs).open(Content, {}, { width: '480px' }).subscribe();
    const close = vi.spyOn(refs[0], 'close');
    const handled = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true });
    handled.preventDefault();

    document.dispatchEvent(handled);

    expect(close).not.toHaveBeenCalled();
  });
});
