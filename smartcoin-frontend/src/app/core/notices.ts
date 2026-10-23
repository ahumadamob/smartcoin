import { inject, Injectable } from '@angular/core';
import { MessageService } from 'primeng/api';

/** Un botón dentro del aviso, como la acción de un snackbar de Material (por ejemplo, «Consolidar»). */
export interface NoticeAction {
  label: string;
  run: () => void;
}

export interface NoticeOptions {
  /** Milisegundos que queda a la vista. */
  duration: number;
  /** `error` cuando algo falló; si no, confirma algo que se hizo. */
  kind?: 'done' | 'error';
  action?: NoticeAction;
}

/**
 * Avisos emergentes (T-19): reemplazan al snackbar de Material en las pantallas que ya usan PrimeNG. Se dibujan en
 * el `p-toast` del layout, que los anuncia como un `status` (cortés, como el snackbar) y muestra la acción si hay.
 */
@Injectable({ providedIn: 'root' })
export class Notices {
  private readonly messages = inject(MessageService);

  show(text: string, options: NoticeOptions): void {
    this.messages.add({
      // Las confirmaciones van oscuras y neutras, como el snackbar de Material; los errores, en rojo.
      severity: options.kind === 'error' ? 'error' : 'contrast',
      detail: text,
      life: options.duration,
      data: options.action ? { action: options.action } : undefined,
    });
  }

  /** Ejecuta la acción de un aviso y lo cierra, como el botón del snackbar. */
  runAction(action: NoticeAction): void {
    this.messages.clear();
    action.run();
  }
}
