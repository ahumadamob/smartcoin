import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { AutenticacinService, LoginResponse } from '../../api';

const SESSION_KEY = 'smartcoin.session';

interface Session {
  token: string;
  /** Instante ISO en que vence el token (el backend lo emite con 8 horas de vigencia). */
  expiresAt: string;
}

/** Sesión del usuario: el token vive en `sessionStorage`, así que se pierde al cerrar la pestaña. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(AutenticacinService);
  private readonly router = inject(Router);
  private readonly session = signal<Session | null>(readSession());

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

  /** Inicia sesión y guarda el token. La respuesta dice si hay que cambiar la contraseña. */
  login(email: string, password: string): Observable<LoginResponse> {
    return this.api
      .login({ email, password })
      .pipe(
        tap((response) => this.store({ token: response.token, expiresAt: response.expiresAt })),
      );
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
      ? { token: parsed.token, expiresAt: parsed.expiresAt }
      : null;
  } catch {
    return null;
  }
}
