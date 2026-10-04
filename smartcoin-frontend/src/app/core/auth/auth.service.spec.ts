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
    });
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

  it('cerrar sesión descarta el token y va al login', () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token: 'guardado', expiresAt: FUTURE }));
    const auth = create();

    auth.logout();

    expect(auth.token()).toBeNull();
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });
});
