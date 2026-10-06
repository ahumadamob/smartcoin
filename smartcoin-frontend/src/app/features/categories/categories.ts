import {
  ChangeDetectionStrategy,
  Component,
  effect,
  ElementRef,
  inject,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  FormGroupDirective,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CategorasService, CategoryResponse } from '../../api';
import { messageFor } from '../../core/error-messages';
import { ConfirmDialog, ConfirmDialogData } from '../../shared/confirm-dialog';

const NAME_MAX_LENGTH = 60;

function notBlank(control: AbstractControl): ValidationErrors | null {
  return typeof control.value === 'string' && control.value.trim() === '' ? { blank: true } : null;
}

function nameControl(): FormControl<string> {
  return new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, notBlank, Validators.maxLength(NAME_MAX_LENGTH)],
  });
}

/**
 * Categorías (HU-09): lista ordenada por nombre con alta, cambio de nombre en la propia fila y eliminación. Solo se
 * valida el formato del nombre; el nombre repetido y la categoría en uso (RN-34) los decide el backend.
 */
@Component({
  selector: 'app-categories',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './categories.html',
  styleUrl: './categories.scss',
})
export class Categories implements OnInit {
  private readonly api = inject(CategorasService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly maxLength = NAME_MAX_LENGTH;
  protected readonly categories = signal<CategoryResponse[]>([]);
  protected readonly loading = signal(true);
  /** Error de la carga de la lista o de una eliminación. */
  protected readonly error = signal<string | null>(null);

  protected readonly newName = nameControl();
  protected readonly addForm = new FormGroup({ name: this.newName });
  protected readonly addError = signal<string | null>(null);
  protected readonly adding = signal(false);

  protected readonly editName = nameControl();
  protected readonly editForm = new FormGroup({ name: this.editName });
  /** La categoría cuyo nombre se está cambiando, o `null`. */
  protected readonly editing = signal<CategoryResponse | null>(null);
  protected readonly editError = signal<string | null>(null);
  protected readonly saving = signal(false);

  private readonly editInput = viewChild<ElementRef<HTMLInputElement>>('editInput');

  constructor() {
    // Al abrir el cambio de nombre, el foco va al campo, así se puede seguir con el teclado.
    effect(() => this.editInput()?.nativeElement.focus());
  }

  ngOnInit(): void {
    this.api.listCategories().subscribe({
      next: (categories) => {
        this.categories.set(categories);
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.error.set(messageFor(e));
        this.loading.set(false);
      },
    });
  }

  protected add(directive: FormGroupDirective): void {
    if (this.newName.invalid) {
      this.newName.markAsTouched();
      return;
    }
    this.addError.set(null);
    this.adding.set(true);
    this.api.createCategory({ name: this.newName.value.trim() }).subscribe({
      next: (category) => {
        this.adding.set(false);
        // El directivo recuerda que el formulario se envió: sin resetearlo a él, el campo vacío se vería inválido.
        directive.resetForm({ name: '' });
        this.reload();
        this.snackBar.open(`Categoría «${category.name}» creada.`, undefined, { duration: 4000 });
      },
      error: (e: unknown) => {
        this.adding.set(false);
        this.addError.set(messageFor(e));
      },
    });
  }

  protected startRename(category: CategoryResponse): void {
    this.error.set(null);
    this.editError.set(null);
    this.editName.reset(category.name);
    this.editing.set(category);
  }

  protected cancelRename(): void {
    this.editing.set(null);
  }

  protected saveRename(): void {
    const category = this.editing();
    if (category === null) {
      return;
    }
    if (this.editName.invalid) {
      this.editName.markAsTouched();
      return;
    }
    this.editError.set(null);
    this.saving.set(true);
    this.api.updateCategory(category.id, { name: this.editName.value.trim() }).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.editing.set(null);
        this.reload();
        this.snackBar.open(`Categoría «${saved.name}» guardada.`, undefined, { duration: 4000 });
      },
      error: (e: unknown) => {
        this.saving.set(false);
        this.editError.set(messageFor(e));
      },
    });
  }

  protected confirmDelete(category: CategoryResponse): void {
    const data: ConfirmDialogData = {
      title: 'Eliminar categoría',
      message: `Vas a eliminar la categoría «${category.name}». Esta acción no se puede deshacer.`,
      confirmLabel: 'Eliminar',
    };
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, { data })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed === true) {
          this.delete(category);
        }
      });
  }

  private delete(category: CategoryResponse): void {
    this.error.set(null);
    this.api.deleteCategory(category.id).subscribe({
      next: () => {
        if (this.editing()?.id === category.id) {
          this.editing.set(null);
        }
        this.reload();
        this.snackBar.open(`Categoría «${category.name}» eliminada.`, undefined, {
          duration: 4000,
        });
      },
      error: (e: unknown) => this.error.set(messageFor(e)),
    });
  }

  private reload(): void {
    this.api.listCategories().subscribe({
      next: (categories) => this.categories.set(categories),
      error: (e: unknown) => this.error.set(messageFor(e)),
    });
  }
}
