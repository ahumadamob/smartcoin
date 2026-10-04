import { HttpErrorResponse } from '@angular/common/http';
import { messageFor } from './error-messages';

const problem = (status: number, body: unknown) => new HttpErrorResponse({ status, error: body });

describe('messageFor', () => {
  it('traduce un code conocido', () => {
    expect(messageFor(problem(401, { code: 'UNAUTHORIZED', detail: 'texto del backend' }))).toBe(
      'Email o contraseña incorrectos.',
    );
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
