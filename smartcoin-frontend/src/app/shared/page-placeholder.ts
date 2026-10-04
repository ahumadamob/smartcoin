import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

/** Pantalla vacía de las rutas que todavía no tienen su historia. */
@Component({
  selector: 'app-page-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<h1>{{ heading }}</h1><p>Pantalla pendiente de implementar.</p>`,
})
export class PagePlaceholder {
  protected readonly heading = inject(ActivatedRoute).snapshot.data['heading'] as string;
}
