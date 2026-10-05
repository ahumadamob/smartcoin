import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { AutenticacinService, LoginResponse } from '../../api';

const SESSION_KEY = 'smartcoin.session';

interface Session {
  token: string;
  /** Instante ISO en que vence el token (el backend lo emite con 8 horas de vigencia). */
  expiresAt: string;
  /** Si el usuario debe cambiar la contraseña antes de usar el resto de la aplicación (HU-04). */
  mustChangePassword: boolean;
}

/** Sesión del usuario: el token vive en `sessionStorage`, así que se pierde al cerrar la pestaña. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(AutenticacinService);
  private readonly router = inject(Router);
  private readonly session = signal<Session | null>(readSession());
  private readonly currentEmail = signal<string | null>(null);

  /** Email del usuario de la sesión, o `null` mientras no se cargó con `loadUser()`. No se guarda en el almacenamiento. */
  readonly email = this.currentEmail.asReadonly();

  /** Token vigente, o `null` si no hay sesión o ya venció. */
  token(): string | null {
    const session = this.session();
    if (session === null) {
      return null;
    }
    if (Date.parse(session.expiresAt) <= Date.now()) {
      this.clear();
      return null;
    }
    return session.token;
  }

  isAuthenticated(): boolean {
    return this.token() !== null;
  }

  /** Con el cambio de contraseña obligatorio pendiente, solo se puede usar la pantalla de cambio (HU-04). */
  mustChangePassword(): boolean {
    return this.isAuthenticated() && this.session()?.mustChangePassword === true;
  }

  /** Inicia sesión y guarda el token. La respuesta dice si hay que cambiar la contraseña. */
  login(email: string, password: string): Observable<LoginResponse> {
    return this.api.login({ email, password }).pipe(
      tap((response) =>
        this.store({
          token: response.token,
          expiresAt: response.expiresAt,
          mustChangePassword: response.mustChangePassword,
        }),
      ),
    );
  }

  /** Cambia la contraseña. El token anterior deja de servir: se reemplaza por el nuevo que devuelve la API. */
  changePassword(currentPassword: string, newPassword: string): Observable<LoginResponse> {
    return this.api.changePassword({ currentPassword, newPassword }).pipe(
      tap((response) =>
        this.store({
          token: response.token,
          expiresAt: response.expiresAt,
          mustChangePassword: response.mustChangePassword,
        }),
      ),
    );
  }

  /**
   * Carga el email del usuario actual (`GET /api/auth/me`). Si falla no hace nada: un 401 ya lo maneja el
   * interceptor y el menú simplemente no muestra el email.
   */
  loadUser(): void {
    this.api.me().subscribe({
      next: (user) => this.currentEmail.set(this.isAuthenticated() ? user.email : null),
      error: () => undefined,
    });
  }

  /** La API respondió 403 `PASSWORD_CHANGE_REQUIRED`: se marca el cambio como pendiente y se va a esa pantalla. */
  requirePasswordChange(): void {
    const session = this.session();
    if (session !== null) {
      this.store({ ...session, mustChangePassword: true });
    }
    void this.router.navigate(['/cambiar-contrasena']);
  }

  /** Descarta el token y vuelve al login. */
  logout(): void {
    this.clear();
    void this.router.navigate(['/login']);
  }

  private store(session: Session): void {
    this.session.set(session);
    try {
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
    } catch {
      // Sin almacenamiento la sesión dura lo que dure la página.
    }
  }

  private clear(): void {
    this.session.set(null);
    this.currentEmail.set(null);
    try {
      sessionStorage.removeItem(SESSION_KEY);
    } catch {
      // Nada que borrar si el almacenamiento no está disponible.
    }
  }
}

function readSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    if (raw === null) {
      return null;
    }
    const parsed: Partial<Session> = JSON.parse(raw);
    return typeof parsed.token === 'string' && typeof parsed.expiresAt === 'string'
      ? {
          token: parsed.token,
          expiresAt: parsed.expiresAt,
          mustChangePassword: parsed.mustChangePassword === true,
        }
      : null;
  } catch {
    return null;
  }
}
