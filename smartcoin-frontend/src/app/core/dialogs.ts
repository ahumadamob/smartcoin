import { afterNextRender, Directive, ElementRef, inject, Injectable, Injector, Type } from '@angular/core';
import { DialogService } from 'primeng/dynamicdialog';
import { AsyncSubject, EMPTY, Observable, take } from 'rxjs';

let nextTitleId = 0;

/** Opciones de un diálogo: solo el ancho; el resto es igual para todos. */
export interface DialogOptions {
  width: string;
}

/**
 * Abre diálogos de PrimeNG (T-19) con las mismas garantías que daba `MatDialog`: modal, se cierra con Escape (sin
 * resultado, como «Cancelar»), lleva el foco adentro al abrir y, al cerrar, lo devuelve al elemento que lo tenía.
 * El título lo pone cada diálogo con la directiva `appDialogTitle`, porque cambia según lo que se hace. El contenido
 * recibe los datos con `inject(DynamicDialogConfig).data` y se cierra con `inject(DynamicDialogRef).close(resultado)`.
 */
@Injectable({ providedIn: 'root' })
export class Dialogs {
  private readonly service = inject(DialogService);

  /**
   * Emite una vez, cuando el diálogo ya se cerró y el foco volvió (como `afterClosed` de Material): el resultado o
   * `undefined` si se canceló. Quien lo abrió puede mover el foco después, por ejemplo si su botón ya no existe.
   */
  open<C, D, R>(component: Type<C>, data: D, options: DialogOptions): Observable<R | undefined> {
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const ref = this.service.open(component, {
      data,
      width: options.width,
      breakpoints: { '700px': '95vw' },
      modal: true,
      styleClass: 'app-dialog',
      showHeader: false,
      // PrimeNG solo escucha Escape si el diálogo se puede cerrar; sin encabezado, igual no muestra la «X».
      closable: true,
      closeOnEscape: true,
      dismissableMask: false,
      focusOnShow: true,
      focusTrap: true,
      draggable: false,
      resizable: false,
    });
    if (ref === null) {
      return EMPTY;
    }
    const closed = new AsyncSubject<R | undefined>();
    let result: R | undefined;
    ref.onClose.pipe(take(1)).subscribe((value: R | undefined) => (result = value));
    ref.onDestroy.pipe(take(1)).subscribe(() => {
      // El foco estaba adentro del diálogo, que ya no existe: vuelve al elemento que lo abrió, si sigue en la página.
      const current = document.activeElement;
      if (trigger?.isConnected && (current === null || current === document.body)) {
        trigger.focus();
      }
      closed.next(result);
      closed.complete();
    });
    return closed.asObservable();
  }
}

/**
 * Título de un diálogo abierto con `Dialogs`: le da un id y lo usa como nombre accesible del diálogo
 * (`aria-labelledby`), que PrimeNG solo pone cuando el título es su propio encabezado.
 */
@Directive({
  selector: '[appDialogTitle]',
  host: { '[id]': 'id', class: 'dialog-title' },
})
export class DialogTitle {
  protected readonly id = `dialog-title-${nextTitleId++}`;

  constructor() {
    const host = inject<ElementRef<HTMLElement>>(ElementRef);
    afterNextRender(
      () => host.nativeElement.closest('[role="dialog"]')?.setAttribute('aria-labelledby', this.id),
      { injector: inject(Injector) },
    );
  }
}
