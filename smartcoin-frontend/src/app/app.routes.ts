import { Routes } from '@angular/router';
import { authGuard, guestGuard, mandatoryPasswordChange } from './core/auth/auth.guard';

const changePassword = () =>
  import('./features/auth/change-password/change-password').then((m) => m.ChangePassword);

const placeholder = () => import('./shared/page-placeholder').then((m) => m.PagePlaceholder);

// Pantallas vacías: cada historia reemplaza su `loadComponent` por la pantalla real.
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login),
    canActivate: [guestGuard],
    title: 'Iniciar sesión',
  },
  {
    // Cambio obligatorio (HU-04): fuera del layout, sin menú ni otra salida que cerrar la sesión. Solo coincide
    // mientras el cambio está pendiente; si no, la misma URL cae en la ruta de abajo.
    path: 'cambiar-contrasena',
    loadComponent: changePassword,
    canMatch: [mandatoryPasswordChange],
    title: 'Cambiar contraseña',
    data: { mandatory: true },
  },
  {
    path: '',
    loadComponent: () => import('./core/layout/layout').then((m) => m.Layout),
    canActivate: [authGuard],
    children: [
      // Cambio voluntario (HU-05): dentro del layout, con el menú y con "Cancelar". Misma URL que el obligatorio.
      {
        path: 'cambiar-contrasena',
        loadComponent: changePassword,
        title: 'Cambiar contraseña',
        data: { mandatory: false },
      },
      { path: '', pathMatch: 'full', redirectTo: 'presupuesto' },
      {
        path: 'presupuesto',
        loadComponent: placeholder,
        title: 'Presupuesto',
        data: { heading: 'Presupuesto del mes' },
      },
      {
        path: 'presupuesto/:period',
        loadComponent: placeholder,
        title: 'Presupuesto',
        data: { heading: 'Presupuesto del mes' },
      },
      {
        path: 'conceptos',
        loadComponent: placeholder,
        title: 'Conceptos',
        data: { heading: 'Conceptos' },
      },
      {
        path: 'cuentas',
        loadComponent: () => import('./features/accounts/accounts').then((m) => m.Accounts),
        title: 'Cuentas',
      },
      {
        path: 'categorias',
        loadComponent: placeholder,
        title: 'Categorías',
        data: { heading: 'Categorías' },
      },
      {
        path: 'transferencias',
        loadComponent: placeholder,
        title: 'Transferencias',
        data: { heading: 'Transferencias' },
      },
      {
        path: 'cierre/:period',
        loadComponent: placeholder,
        title: 'Cierre de mes',
        data: { heading: 'Cierre de mes' },
      },
      {
        path: 'flujo-de-caja',
        loadComponent: placeholder,
        title: 'Flujo de caja',
        data: { heading: 'Flujo de caja' },
      },
      {
        path: 'proyeccion',
        loadComponent: placeholder,
        title: 'Proyección',
        data: { heading: 'Proyección' },
      },
      {
        path: 'planificacion',
        loadComponent: placeholder,
        title: 'Varios meses',
        data: { heading: 'Varios meses' },
      },
    ],
  },
  { path: '**', redirectTo: 'presupuesto' },
];
