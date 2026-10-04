import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  let auth: { token: ReturnType<typeof vi.fn>; logout: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    auth = { token: vi.fn().mockReturnValue('el.token'), logout: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  it('agrega Authorization: Bearer cuando hay token', () => {
    http.get('/api/auth/me').subscribe();

    const request = controller.expectOne('/api/auth/me');
    expect(request.request.headers.get('Authorization')).toBe('Bearer el.token');
    request.flush({});
  });

  it('no agrega el header sin token', () => {
    auth.token.mockReturnValue(null);
    http.get('/api/auth/me').subscribe();

    const request = controller.expectOne('/api/auth/me');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('no agrega el header al login, aunque haya un token viejo', () => {
    http.post('/api/auth/login', {}).subscribe();

    const request = controller.expectOne('/api/auth/login');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('ante un 401 cierra la sesión y propaga el error', () => {
    let status: number | undefined;
    http.get('/api/accounts').subscribe({ error: (e) => (status = e.status) });

    controller
      .expectOne('/api/accounts')
      .flush({ code: 'UNAUTHORIZED' }, { status: 401, statusText: 'Unauthorized' });

    expect(auth.logout).toHaveBeenCalledOnce();
    expect(status).toBe(401);
  });

  it('un 401 del login no cierra la sesión: lo muestra la pantalla', () => {
    http.post('/api/auth/login', {}).subscribe({ error: () => undefined });

    controller
      .expectOne('/api/auth/login')
      .flush({ code: 'UNAUTHORIZED' }, { status: 401, statusText: 'Unauthorized' });

    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('otros errores no cierran la sesión', () => {
    http.get('/api/accounts').subscribe({ error: () => undefined });

    controller
      .expectOne('/api/accounts')
      .flush({ code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });

    expect(auth.logout).not.toHaveBeenCalled();
  });
});
