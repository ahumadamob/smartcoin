import { HttpErrorResponse } from '@angular/common/http';
import { messageFor } from './error-messages';

const problem = (status: number, body: unknown) => new HttpErrorResponse({ status, error: body });

describe('messageFor', () => {
  it('traduce un code conocido', () => {
    expect(messageFor(problem(401, { code: 'UNAUTHORIZED', detail: 'texto del backend' }))).toBe(
      'Email o contraseña incorrectos.',
    );
  });

  it('traduce la contraseña actual incorrecta', () => {
    expect(
      messageFor(problem(400, { code: 'INVALID_CURRENT_PASSWORD', detail: 'texto del backend' })),
    ).toBe('La contraseña actual es incorrecta.');
  });

  it.each([
    ['ACCOUNT_NAME_TAKEN', 'Ya tenés una cuenta con ese nombre.'],
    [
      'ACCOUNT_IN_USE',
      'No se puede eliminar la cuenta porque ya tiene Conceptos, partidas, movimientos, transferencias o cierres.',
    ],
    ['CATEGORY_NAME_TAKEN', 'Ya tenés una categoría con ese nombre.'],
    ['CATEGORY_IN_USE', 'No se puede eliminar la categoría porque la usan Conceptos o partidas.'],
    [
      'PERIOD_NOT_AVAILABLE',
      'Ese período no está disponible: tiene que estar entre tu primer período abierto y el horizonte.',
    ],
    ['CURRENCY_MISMATCH', 'La cuenta elegida es de otra moneda. Elegí una cuenta en la misma moneda.'],
    ['FIELD_NOT_EDITABLE', 'Ese dato ya no se puede editar. Actualizá la pantalla y probá de nuevo.'],
  ])('traduce %s', (code, expected) => {
    expect(messageFor(problem(409, { code, detail: 'texto del backend' }))).toBe(expected);
  });

  describe('VALIDATION_ERROR', () => {
    it('muestra el primer error por campo', () => {
      const body = {
        code: 'VALIDATION_ERROR',
        detail: 'La solicitud tiene datos inválidos.',
        errors: [
          { field: 'newPassword', message: 'La contraseña debe tener al menos 10 caracteres.' },
        ],
      };

      expect(messageFor(problem(400, body))).toBe(
        'La contraseña debe tener al menos 10 caracteres.',
      );
    });

    it('sin errores por campo muestra el detail', () => {
      const body = {
        code: 'VALIDATION_ERROR',
        detail: 'La contraseña nueva debe ser distinta de la actual.',
      };

      expect(messageFor(problem(400, body))).toBe(
        'La contraseña nueva debe ser distinta de la actual.',
      );
    });

    it('sin errores ni detail usa un texto genérico', () => {
      expect(messageFor(problem(400, { code: 'VALIDATION_ERROR' }))).toBe(
        'Revisá los datos ingresados.',
      );
    });
  });

  it('con un code desconocido usa el detail del backend', () => {
    expect(messageFor(problem(409, { code: 'ALGO_NUEVO', detail: 'Detalle en español.' }))).toBe(
      'Detalle en español.',
    );
  });

  it('sin code ni detail usa un mensaje genérico', () => {
    expect(messageFor(problem(500, {}))).toBe('Ocurrió un error inesperado.');
    expect(messageFor(problem(500, null))).toBe('Ocurrió un error inesperado.');
  });

  it('sin conexión avisa que no se pudo conectar', () => {
    expect(messageFor(problem(0, null))).toContain('No se pudo conectar');
  });

  it('un error que no es de HTTP usa el mensaje genérico', () => {
    expect(messageFor(new Error('x'))).toBe('Ocurrió un error inesperado.');
  });
});
