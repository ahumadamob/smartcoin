import { HttpErrorResponse } from '@angular/common/http';

/** Texto en español de cada `code` de Problem Details (docs/reglas-de-negocio.md). Los que faltan caen en el `detail`. */
const MESSAGES: Record<string, string> = {
  // La API solo devuelve 401 con credenciales incorrectas o con una sesión que ya no sirve.
  UNAUTHORIZED: 'Email o contraseña incorrectos.',
  INVALID_CURRENT_PASSWORD: 'La contraseña actual es incorrecta.',
};

const VALIDATION_ERROR = 'VALIDATION_ERROR';
const VALIDATION_FALLBACK = 'Revisá los datos ingresados.';

const NETWORK_ERROR = 'No se pudo conectar con el servidor. Probá de nuevo en unos minutos.';
const UNEXPECTED_ERROR = 'Ocurrió un error inesperado.';

interface ProblemBody {
  code?: string;
  detail?: string;
  /** Errores por campo de un `VALIDATION_ERROR` de Bean Validation. */
  errors?: { field?: string; message?: string }[];
}

/**
 * Mensaje para mostrar al usuario ante un error de la API: por `code`, si no por `detail`, si no uno genérico.
 * Un `VALIDATION_ERROR` explica qué falló: el primer error por campo o el `detail` (por ejemplo, "la contraseña nueva
 * debe ser distinta de la actual"); sin ninguno de los dos, un texto genérico.
 */
export function messageFor(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) {
    return UNEXPECTED_ERROR;
  }
  if (error.status === 0) {
    return NETWORK_ERROR;
  }
  const body: ProblemBody | null = typeof error.error === 'object' ? error.error : null;
  if (body?.code === VALIDATION_ERROR) {
    return body.errors?.[0]?.message || body.detail || VALIDATION_FALLBACK;
  }
  return (body?.code && MESSAGES[body.code]) || body?.detail || UNEXPECTED_ERROR;
}
