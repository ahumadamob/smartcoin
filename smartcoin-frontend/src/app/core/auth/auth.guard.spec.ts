import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { authGuard, guestGuard, sessionGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('guards de autenticación', () => {
  let isAuthenticated: boolean;
  let mustChangePassword: boolean;
  let router: Router;

  beforeEach(() => {
    isAuthenticated = false;
    mustChangePassword = false;
    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => isAuthenticated,
            mustChangePassword: () => mustChangePassword,
          },
        },
      ],
    });
    router = TestBed.inject(Router);
  });

  const run = (guard: typeof authGuard) =>
    TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    );

  describe('authGuard', () => {
    it('sin token redirige a /login', () => {
      const result = run(authGuard) as UrlTree;

      expect(router.serializeUrl(result)).toBe('/login');
    });

    it('con token y sin cambio pendiente deja pasar', () => {
      isAuthenticated = true;

      expect(run(authGuard)).toBe(true);
    });

    it('con el cambio de contraseña pendiente redirige a /cambiar-contrasena', () => {
      isAuthenticated = true;
      mustChangePassword = true;
      const result = run(authGuard) as UrlTree;

      expect(router.serializeUrl(result)).toBe('/cambiar-contrasena');
    });
  });

  describe('sessionGuard', () => {
    it('sin token redirige a /login', () => {
      const result = run(sessionGuard) as UrlTree;

      expect(router.serializeUrl(result)).toBe('/login');
    });

    it('con token deja pasar, tenga o no el cambio pendiente', () => {
      isAuthenticated = true;
      expect(run(sessionGuard)).toBe(true);

      mustChangePassword = true;
      expect(run(sessionGuard)).toBe(true);
    });
  });

  describe('guestGuard', () => {
    it('sin token deja entrar al login', () => {
      expect(run(guestGuard)).toBe(true);
    });

    it('con token lleva al presupuesto', () => {
      isAuthenticated = true;
      const result = run(guestGuard) as UrlTree;

      expect(router.serializeUrl(result)).toBe('/presupuesto');
    });

    it('con token y el cambio pendiente lleva a /cambiar-contrasena', () => {
      isAuthenticated = true;
      mustChangePassword = true;
      const result = run(guestGuard) as UrlTree;

      expect(router.serializeUrl(result)).toBe('/cambiar-contrasena');
    });
  });
});
