import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guard';
import { Layout } from './core/layout/layout';

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
    path: 'cambiar-contrasena',
    loadComponent: placeholder,
    canActivate: [authGuard],
    title: 'Cambiar contraseña',
    data: { heading: 'Cambiar contraseña' },
  },
  {
    path: '',
    component: Layout,
    canActivate: [authGuard],
    children: [
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
        loadComponent: placeholder,
        title: 'Cuentas',
        data: { heading: 'Cuentas' },
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
