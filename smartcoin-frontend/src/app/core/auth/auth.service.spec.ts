import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideApi } from '../../api';
import { AuthService } from './auth.service';

const SESSION_KEY = 'smartcoin.session';
const FUTURE = '2999-01-01T00:00:00Z';

describe('AuthService', () => {
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.fn>;

  function create(): AuthService {
    navigate = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApi(''),
        { provide: Router, useValue: { navigate } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(AuthService);
  }

  beforeEach(() => sessionStorage.clear());

  it('sin sesión guardada no hay token', () => {
    const auth = create();

    expect(auth.token()).toBeNull();
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('el login guarda el token en sessionStorage y devuelve mustChangePassword', () => {
    const auth = create();
    let mustChangePassword: boolean | undefined;

    auth
      .login('persona@ejemplo.com', 'secreta')
      .subscribe((r) => (mustChangePassword = r.mustChangePassword));
    const request = http.expectOne('/api/auth/login');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ email: 'persona@ejemplo.com', password: 'secreta' });
    request.flush({ token: 'el.token', expiresAt: FUTURE, mustChangePassword: true });

    expect(mustChangePassword).toBe(true);
    expect(auth.token()).toBe('el.token');
    expect(auth.isAuthenticated()).toBe(true);
    expect(JSON.parse(sessionStorage.getItem(SESSION_KEY)!)).toEqual({
      token: 'el.token',
      expiresAt: FUTURE,
      mustChangePassword: true,
    });
    expect(auth.mustChangePassword()).toBe(true);
  });

  it('un login sin cambio pendiente no lo marca', () => {
    const auth = create();

    auth.login('persona@ejemplo.com', 'secreta').subscribe();
    http
      .expectOne('/api/auth/login')
      .flush({ token: 'el.token', expiresAt: FUTURE, mustChangePassword: false });

    expect(auth.mustChangePassword()).toBe(false);
  });

  it('un login fallido no guarda nada', () => {
    const auth = create();
    let failed = false;

    auth.login('persona@ejemplo.com', 'mala').subscribe({ error: () => (failed = true) });
    http
      .expectOne('/api/auth/login')
      .flush({ code: 'UNAUTHORIZED' }, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBe(true);
    expect(auth.token()).toBeNull();
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it('recupera la sesión guardada al crearse', () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token: 'guardado', expiresAt: FUTURE }));

    expect(create().token()).toBe('guardado');
  });

  it('descarta una sesión guardada que ya venció', () => {
    sessionStorage.setItem(
      SESSION_KEY,
      JSON.stringify({ token: 'viejo', expiresAt: '2000-01-01T00:00:00Z' }),
    );
    const auth = create();

    expect(auth.token()).toBeNull();
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it('ignora una sesión guardada con formato inválido', () => {
    sessionStorage.setItem(SESSION_KEY, 'no es json');

    expect(create().isAuthenticated()).toBe(false);
  });

  it('una sesión guardada sin el dato de cambio pendiente no lo exige', () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token: 'guardado', expiresAt: FUTURE }));

    expect(create().mustChangePassword()).toBe(false);
  });

  it('sin sesión no hay cambio pendiente', () => {
    expect(create().mustChangePassword()).toBe(false);
  });

  describe('changePassword', () => {
    it('envía las dos contraseñas y reemplaza el token por el nuevo', () => {
      sessionStorage.setItem(
        SESSION_KEY,
        JSON.stringify({ token: 'viejo', expiresAt: FUTURE, mustChangePassword: true }),
      );
      const auth = create();

      auth.changePassword('la-actual', 'la-nueva-123').subscribe();
      const request = http.expectOne('/api/auth/change-password');
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({
        currentPassword: 'la-actual',
        newPassword: 'la-nueva-123',
      });
      request.flush({ token: 'nuevo', expiresAt: FUTURE, mustChangePassword: false });

      expect(auth.token()).toBe('nuevo');
      expect(auth.mustChangePassword()).toBe(false);
      expect(JSON.parse(sessionStorage.getItem(SESSION_KEY)!)).toEqual({
        token: 'nuevo',
        expiresAt: FUTURE,
        mustChangePassword: false,
      });
    });

    it('si falla, conserva el token y el cambio pendiente', () => {
      sessionStorage.setItem(
        SESSION_KEY,
        JSON.stringify({ token: 'viejo', expiresAt: FUTURE, mustChangePassword: true }),
      );
      const auth = create();
      let failed = false;

      auth.changePassword('mala', 'la-nueva-123').subscribe({ error: () => (failed = true) });
      http
        .expectOne('/api/auth/change-password')
        .flush({ code: 'INVALID_CURRENT_PASSWORD' }, { status: 400, statusText: 'Bad Request' });

      expect(failed).toBe(true);
      expect(auth.token()).toBe('viejo');
      expect(auth.mustChangePassword()).toBe(true);
    });
  });

  describe('loadUser', () => {
    beforeEach(() =>
      sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token: 'guardado', expiresAt: FUTURE })),
    );

    it('guarda el email del usuario actual sin tocar el almacenamiento', () => {
      const auth = create();
      expect(auth.email()).toBeNull();

      auth.loadUser();
      const request = http.expectOne('/api/auth/me');
      expect(request.request.method).toBe('GET');
      request.flush({
        id: 1,
        email: 'persona@ejemplo.com',
        mustChangePassword: false,
        startPeriod: '2026-08',
      });

      expect(auth.email()).toBe('persona@ejemplo.com');
      expect(sessionStorage.getItem(SESSION_KEY)).not.toContain('persona@ejemplo.com');
    });

    it('si falla, el email queda sin cargar', () => {
      const auth = create();

      auth.loadUser();
      http
        .expectOne('/api/auth/me')
        .flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Error' });

      expect(auth.email()).toBeNull();
    });

    it('cerrar sesión borra el email', () => {
      const auth = create();
      auth.loadUser();
      http.expectOne('/api/auth/me').flush({
        id: 1,
        email: 'persona@ejemplo.com',
        mustChangePassword: false,
        startPeriod: '2026-08',
      });

      auth.logout();

      expect(auth.email()).toBeNull();
    });

    it('si la sesión terminó antes de la respuesta, no guarda el email', () => {
      const auth = create();
      auth.loadUser();
      auth.logout();

      http.expectOne('/api/auth/me').flush({
        id: 1,
        email: 'persona@ejemplo.com',
        mustChangePassword: false,
        startPeriod: '2026-08',
      });

      expect(auth.email()).toBeNull();
    });
  });

  describe('requirePasswordChange', () => {
    it('marca el cambio como pendiente, lo guarda y va a la pantalla de cambio', () => {
      sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token: 'guardado', expiresAt: FUTURE }));
      const auth = create();

      auth.requirePasswordChange();

      expect(auth.mustChangePassword()).toBe(true);
      expect(auth.token()).toBe('guardado');
      expect(JSON.parse(sessionStorage.getItem(SESSION_KEY)!).mustChangePassword).toBe(true);
      expect(navigate).toHaveBeenCalledWith(['/cambiar-contrasena']);
    });

    it('sin sesión solo navega', () => {
      const auth = create();

      auth.requirePasswordChange();

      expect(auth.isAuthenticated()).toBe(false);
      expect(navigate).toHaveBeenCalledWith(['/cambiar-contrasena']);
    });
  });

  it('cerrar sesión descarta el token y va al login', () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token: 'guardado', expiresAt: FUTURE }));
    const auth = create();

    auth.logout();

    expect(auth.token()).toBeNull();
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });
});
