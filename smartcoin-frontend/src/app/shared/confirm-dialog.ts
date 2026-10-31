import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Button } from 'primeng/button';
import { ButtonGroup } from 'primeng/buttongroup';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

export interface ConfirmDialogData {
  title: string;
  /** Qué va a pasar exactamente, en una o dos frases. */
  message: string;
  confirmLabel: string;
}

/**
 * Diálogo de confirmación: se cierra con `true` si el usuario confirma y con `false` (o `undefined` si lo descarta)
 * si no. Quien lo abre muestra en `message` exactamente qué va a pasar.
 */
@Component({
  selector: 'app-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Button, ButtonGroup, MatDialogModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>{{ data.message }}</mat-dialog-content>
    <mat-dialog-actions align="end">
      <p-buttongroup>
        <p-button class="action" [fluid]="true" type="button" label="Cancelar" severity="info" [mat-dialog-close]="false">
          <ng-template #icon><span class="material-symbols-outlined" aria-hidden="true">close</span></ng-template>
        </p-button>
        <p-button
          class="action"
          [fluid]="true"
          type="button"
          [label]="data.confirmLabel"
          [severity]="destructive ? 'danger' : undefined"
          [mat-dialog-close]="true"
        >
          <ng-template #icon>
            <span class="material-symbols-outlined" aria-hidden="true">{{ destructive ? 'delete_forever' : 'check' }}</span>
          </ng-template>
        </p-button>
      </p-buttongroup>
    </mat-dialog-actions>
  `,
})
export class ConfirmDialog {
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
  /** «Eliminar …» confirma en rojo (GUIA-BOTONES.md): lo demás, en el color principal. */
  protected readonly destructive = this.data.confirmLabel.startsWith('Eliminar');
}
