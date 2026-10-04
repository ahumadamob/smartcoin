import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { AuthService } from '../../../core/auth/auth.service';
import { messageFor } from '../../../core/error-messages';

/** Mínimo de la política de contraseñas (RN-51). Solo se valida el formato: el resto lo decide el backend. */
export const PASSWORD_MIN_LENGTH = 10;

/** La repetición debe coincidir con la contraseña nueva. Vacía no se marca: ya la señala `required`. */
function matchesNewPassword(control: AbstractControl): ValidationErrors | null {
  const repeated = control.value as string;
  const newPassword = control.parent?.get('newPassword')?.value as string | undefined;
  return repeated === '' || repeated === newPassword ? null : { mismatch: true };
}

/**
 * Formulario de cambio de contraseña (HU-04, HU-05): contraseña actual, nueva y repetición de la nueva. Al
 * cambiarla, `AuthService` reemplaza el token y el formulario avisa con `changed`; qué hacer después lo decide quien
 * lo usa (ir al presupuesto, cerrar un diálogo).
 */
@Component({
  selector: 'app-change-password-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './change-password-form.html',
  styleUrl: './change-password-form.scss',
})
export class ChangePasswordForm {
  private readonly auth = inject(AuthService);

  /** La contraseña ya se cambió y el token nuevo ya está guardado. */
  readonly changed = output<void>();

  protected readonly minLength = PASSWORD_MIN_LENGTH;
  protected readonly form = inject(FormBuilder).nonNullable.group({
    currentPassword: ['', Validators.required],
    newPassword: ['', [Validators.required, Validators.minLength(PASSWORD_MIN_LENGTH)]],
    repeatPassword: ['', [Validators.required, matchesNewPassword]],
  });
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);

  constructor() {
    // Si cambia la nueva, hay que volver a comparar la repetición.
    this.form.controls.newPassword.valueChanges
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => this.form.controls.repeatPassword.updateValueAndValidity());
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { currentPassword, newPassword } = this.form.getRawValue();
    this.error.set(null);
    this.submitting.set(true);
    this.auth.changePassword(currentPassword, newPassword).subscribe({
      next: () => {
        this.submitting.set(false);
        this.form.reset();
        this.changed.emit();
      },
      error: (e: unknown) => {
        this.submitting.set(false);
        this.error.set(messageFor(e));
      },
    });
  }
}
