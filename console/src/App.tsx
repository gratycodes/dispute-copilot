import { DisputePage } from './DisputePage';
import { LabelsPage } from './LabelsPage';
import { QueuePage } from './QueuePage';
import { useHashRoute } from './useHashRoute';

export function App() {
  const route = useHashRoute();
  return (
    <div className="app">
      <header className="top">
        <a href="#/" className="brand">
          Dispute review
        </a>
        <nav>
          <a href="#/" className={route.page !== 'labels' ? 'active' : ''}>
            Queue
          </a>
          <a href="#/labels" className={route.page === 'labels' ? 'active' : ''}>
            Overrides
          </a>
        </nav>
        <span className="synthetic">Synthetic data</span>
      </header>
      <main>
        {route.page === 'queue' && <QueuePage />}
        {route.page === 'dispute' && <DisputePage key={route.id} id={route.id} />}
        {route.page === 'labels' && <LabelsPage />}
      </main>
    </div>
  );
}
