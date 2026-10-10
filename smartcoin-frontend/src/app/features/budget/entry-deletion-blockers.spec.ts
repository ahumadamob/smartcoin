import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DeletionBlocker } from '../../api';
import { provideLocale } from '../../core/locale';
import { EntryDeletionBlockers } from './entry-deletion-blockers';

describe('EntryDeletionBlockers (HU-18)', () => {
  let fixture: ComponentFixture<EntryDeletionBlockers>;

  async function render(blockers: DeletionBlocker[]) {
    await TestBed.configureTestingModule({ imports: [EntryDeletionBlockers], providers: [provideLocale()] }).compileComponents();
    fixture = TestBed.createComponent(EntryDeletionBlockers);
    fixture.componentRef.setInput('blockers', blockers);
    await fixture.whenStable();
  }

  const items = () =>
    Array.from(fixture.nativeElement.querySelectorAll('[data-testid="blocker"]')).map((li) =>
      ((li as HTMLElement).textContent ?? '').replace(/\s+/g, ' ').trim(),
    );
  const intro = () => (fixture.nativeElement.querySelector('.intro') as HTMLElement).textContent!.trim();

  it('lista cada partida con su mes y su motivo, en el orden del backend', async () => {
    await render([
      { entryId: 11, period: '2026-12', reason: 'HAS_MOVEMENTS' },
      { entryId: 12, period: '2027-01', reason: 'CONSOLIDATED' },
    ]);

    expect(items()).toEqual(['diciembre 2026: tiene movimientos', 'enero 2027: está consolidada']);
    expect(intro()).toBe('No se puede eliminar porque estas partidas lo impiden:');
  });

  it('con una sola partida lo dice en singular', async () => {
    await render([{ entryId: 11, period: '2026-12', reason: 'CONSOLIDATED' }]);

    expect(items()).toEqual(['diciembre 2026: está consolidada']);
    expect(intro()).toBe('No se puede eliminar porque esta partida lo impide:');
  });
});
