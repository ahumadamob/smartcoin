import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { CategorasService, CategoryResponse } from '../../api';
import { Categories } from './categories';

const category = (id: number, name: string): CategoryResponse => ({ id, name });
const problem = (status: number, code: string) =>
  throwError(() => new HttpErrorResponse({ status, error: { code, detail: 'texto del backend' } }));

describe('Categories', () => {
  let fixture: ComponentFixture<Categories>;
  let api: {
    listCategories: ReturnType<typeof vi.fn>;
    createCategory: ReturnType<typeof vi.fn>;
    updateCategory: ReturnType<typeof vi.fn>;
    deleteCategory: ReturnType<typeof vi.fn>;
  };
  let dialogAnswer: boolean | undefined;
  let dialogOpened: ReturnType<typeof vi.fn<(...args: unknown[]) => void>>;
  let snackBar: { open: ReturnType<typeof vi.fn> };

  async function setup(categories: CategoryResponse[], listResult = of(categories)) {
    api = {
      listCategories: vi.fn().mockReturnValue(listResult),
      createCategory: vi.fn(),
      updateCategory: vi.fn(),
      deleteCategory: vi.fn().mockReturnValue(of(undefined)),
    };
    dialogOpened = vi.fn<(...args: unknown[]) => void>();
    snackBar = { open: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [Categories],
      providers: [
        { provide: CategorasService, useValue: api },
        {
          provide: MatDialog,
          useValue: {
            open: (...args: unknown[]) => {
              dialogOpened(...args);
              return { afterClosed: () => of(dialogAnswer) };
            },
          },
        },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Categories);
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';
  const button = (label: string) =>
    Array.from(root().querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label,
    )!;
  const click = (label: string) => {
    button(label).click();
    fixture.detectChanges();
  };
  const names = () => Array.from(root().querySelectorAll('li .name')).map((n) => n.textContent);
  const field = (label: string) =>
    Array.from(root().querySelectorAll('mat-form-field'))
      .find((f) => f.querySelector('label')?.textContent?.includes(label))!
      .querySelector('input')!;
  const type = (input: HTMLInputElement, value: string) => {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  const submit = (formSelector: string) => {
    root().querySelector<HTMLFormElement>(formSelector)!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  };
  const alert = () => root().querySelector('[role="alert"]')?.textContent ?? '';

  it('lista las categorías en el orden que informa el backend', async () => {
    await setup([category(2, 'Hogar'), category(1, 'Impuestos')]);

    expect(names()).toEqual(['Hogar', 'Impuestos']);
  });

  it('sin categorías lo dice y aclara que son opcionales', async () => {
    await setup([]);

    expect(text()).toContain('Todavía no creaste ninguna categoría');
    expect(text()).toContain('opcionales');
  });

  it('todos los campos y botones tienen etiqueta', async () => {
    await setup([category(1, 'Hogar')]);
    click('Renombrar Hogar');

    expect(field('Nueva categoría')).toBeTruthy();
    expect(field('Nombre de la categoría')).toBeTruthy();
    expect(button('Renombrar Hogar')).toBeUndefined();
    expect(button('Eliminar Hogar')).toBeUndefined();
    expect(root().querySelector('ul')?.getAttribute('aria-label')).toBe('Categorías');
  });

  describe('alta', () => {
    it('crea la categoría con el nombre recortado, vacía el campo, recarga y avisa', async () => {
      await setup([]);
      api.createCategory.mockReturnValue(of(category(5, 'Impuestos')));
      api.listCategories.mockReturnValue(of([category(5, 'Impuestos')]));
      type(field('Nueva categoría'), '  Impuestos ');
      submit('form.add');

      expect(api.createCategory).toHaveBeenCalledWith({ name: 'Impuestos' });
      expect(api.listCategories).toHaveBeenCalledTimes(2);
      expect(names()).toEqual(['Impuestos']);
      expect(field('Nueva categoría').value).toBe('');
      expect(snackBar.open).toHaveBeenCalledWith(
        'Categoría «Impuestos» creada.',
        undefined,
        expect.anything(),
      );
    });

    it('un nombre vacío o en blanco no se envía y pide el nombre', async () => {
      await setup([]);
      type(field('Nueva categoría'), '   ');
      submit('form.add');

      expect(api.createCategory).not.toHaveBeenCalled();
      expect(text()).toContain('Ingresá el nombre de la categoría.');
    });

    it('el campo no admite más de 60 caracteres', async () => {
      await setup([]);

      expect(field('Nueva categoría').getAttribute('maxlength')).toBe('60');
    });

    it('con el nombre repetido muestra el error del backend y no agrega nada', async () => {
      await setup([category(1, 'Hogar')]);
      api.createCategory.mockReturnValue(problem(409, 'CATEGORY_NAME_TAKEN'));
      type(field('Nueva categoría'), 'hogar');
      submit('form.add');

      expect(alert()).toBe('Ya tenés una categoría con ese nombre.');
      expect(names()).toEqual(['Hogar']);
      expect(snackBar.open).not.toHaveBeenCalled();
      expect(field('Nueva categoría').value).toBe('hogar');
    });
  });

  describe('cambio de nombre', () => {
    it('«Renombrar» abre el campo con el nombre actual y le da el foco', async () => {
      await setup([category(1, 'Hogar')]);
      click('Renombrar Hogar');
      fixture.detectChanges();

      const input = field('Nombre de la categoría');
      expect(input.value).toBe('Hogar');
      expect(document.activeElement).toBe(input);
    });

    it('guarda el nombre nuevo recortado, recarga y avisa', async () => {
      await setup([category(1, 'Hogar')]);
      api.updateCategory.mockReturnValue(of(category(1, 'Casa')));
      api.listCategories.mockReturnValue(of([category(1, 'Casa')]));
      click('Renombrar Hogar');
      type(field('Nombre de la categoría'), ' Casa ');
      submit('form.edit');

      expect(api.updateCategory).toHaveBeenCalledWith(1, { name: 'Casa' });
      expect(names()).toEqual(['Casa']);
      expect(root().querySelector('form.edit')).toBeNull();
      expect(snackBar.open).toHaveBeenCalledWith(
        'Categoría «Casa» guardada.',
        undefined,
        expect.anything(),
      );
    });

    it('con un nombre tomado muestra el error en la fila y deja abierto el campo', async () => {
      await setup([category(1, 'Hogar'), category(2, 'Servicios')]);
      api.updateCategory.mockReturnValue(problem(409, 'CATEGORY_NAME_TAKEN'));
      click('Renombrar Hogar');
      type(field('Nombre de la categoría'), 'Servicios');
      submit('form.edit');

      expect(alert()).toBe('Ya tenés una categoría con ese nombre.');
      expect(root().querySelector('li form.edit [role="alert"], li [role="alert"]')).toBeTruthy();
      expect(field('Nombre de la categoría').value).toBe('Servicios');
      expect(names()).toEqual(['Servicios']);
    });

    it('un nombre en blanco no se envía', async () => {
      await setup([category(1, 'Hogar')]);
      click('Renombrar Hogar');
      type(field('Nombre de la categoría'), '  ');
      submit('form.edit');

      expect(api.updateCategory).not.toHaveBeenCalled();
      expect(text()).toContain('Ingresá el nombre de la categoría.');
    });

    it('Cancelar cierra el campo sin guardar', async () => {
      await setup([category(1, 'Hogar')]);
      click('Renombrar Hogar');
      click('Cancelar');

      expect(api.updateCategory).not.toHaveBeenCalled();
      expect(names()).toEqual(['Hogar']);
    });

    it('Escape cierra el campo sin guardar', async () => {
      await setup([category(1, 'Hogar')]);
      click('Renombrar Hogar');
      root()
        .querySelector('form.edit')!
        .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      fixture.detectChanges();

      expect(root().querySelector('form.edit')).toBeNull();
      expect(api.updateCategory).not.toHaveBeenCalled();
    });
  });

  describe('eliminar', () => {
    it('pide confirmación antes de eliminar y dice qué categoría es', async () => {
      await setup([category(1, 'Hogar')]);
      dialogAnswer = false;
      click('Eliminar Hogar');

      expect(dialogOpened).toHaveBeenCalledOnce();
      const config = dialogOpened.mock.calls[0][1] as { data: { message: string } };
      expect(config.data.message).toContain('«Hogar»');
      expect(api.deleteCategory).not.toHaveBeenCalled();
    });

    it('si se descarta el diálogo no elimina nada', async () => {
      await setup([category(1, 'Hogar')]);
      dialogAnswer = undefined;
      click('Eliminar Hogar');

      expect(api.deleteCategory).not.toHaveBeenCalled();
    });

    it('al confirmar elimina, recarga la lista y avisa', async () => {
      await setup([category(1, 'Hogar')]);
      api.listCategories.mockReturnValue(of([]));
      dialogAnswer = true;
      click('Eliminar Hogar');

      expect(api.deleteCategory).toHaveBeenCalledWith(1);
      expect(names()).toEqual([]);
      expect(text()).toContain('Todavía no creaste ninguna categoría');
      expect(snackBar.open).toHaveBeenCalledWith(
        'Categoría «Hogar» eliminada.',
        undefined,
        expect.anything(),
      );
    });

    it('si la categoría está en uso muestra el error y sigue en la lista', async () => {
      await setup([category(1, 'Hogar')]);
      api.deleteCategory.mockReturnValue(problem(409, 'CATEGORY_IN_USE'));
      dialogAnswer = true;
      click('Eliminar Hogar');

      expect(alert()).toContain('No se puede eliminar la categoría');
      expect(names()).toEqual(['Hogar']);
    });
  });

  it('si no se pueden cargar las categorías muestra el error', async () => {
    await setup(
      [],
      throwError(() => new HttpErrorResponse({ status: 0 })),
    );

    expect(alert()).toContain('No se pudo conectar con el servidor');
  });
});
