import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { authGuard, guestGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('guards de autenticación', () => {
  let isAuthenticated: boolean;
  let router: Router;

  beforeEach(() => {
    isAuthenticated = false;
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { isAuthenticated: () => isAuthenticated } }],
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

    it('con token deja pasar', () => {
      isAuthenticated = true;

      expect(run(authGuard)).toBe(true);
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
  });
});
