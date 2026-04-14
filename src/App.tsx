import { useEffect, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { db } from './lib/db';
import { RecommendationTemplatesProvider } from './lib/recommendedTemplates';
import { initializeDatabase } from './lib/seed';
import { ensurePersistentStorage } from './lib/storage';
import { HistoryPage } from './features/history/HistoryPage';
import { SessionDetailPage } from './features/history/SessionDetailPage';
import { RoutinesPage } from './features/routines/RoutinesPage';
import { SettingsPage } from './features/settings/SettingsPage';
import { SetupPage } from './features/setup/SetupPage';
import { SessionPage } from './features/today/SessionPage';
import { TodayPage } from './features/today/TodayPage';

function AppRoutes({ ready }: { ready: boolean }) {
  const profile = useLiveQuery(() => db.profile.get('local-profile'), [], null);
  const location = useLocation();

  if (!ready) {
    return (
      <div className="app-shell">
        <div className="loading-screen">
          <div className="loading-screen__badge">오늘운동 준비 중</div>
          <h1>오늘운동을 불러오는 중입니다.</h1>
          <p>운동 기록과 추천 루틴을 정리하고 있어요.</p>
        </div>
      </div>
    );
  }

  if (!profile && location.pathname !== '/setup') {
    return <Navigate to="/setup" replace />;
  }

  if (profile && location.pathname === '/setup') {
    return <Navigate to="/today" replace />;
  }

  return (
    <AppShell>
      <Routes>
        <Route path="/" element={<Navigate to={profile ? '/today' : '/setup'} replace />} />
        <Route path="/setup" element={<SetupPage />} />
        <Route path="/today" element={<TodayPage />} />
        <Route path="/today/session/:routineId" element={<SessionPage />} />
        <Route path="/routines" element={<RoutinesPage />} />
        <Route path="/history" element={<HistoryPage />} />
        <Route path="/history/:sessionId" element={<SessionDetailPage />} />
        <Route path="/history/:sessionId/edit" element={<SessionPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Routes>
    </AppShell>
  );
}

export default function App() {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let active = true;

    void initializeDatabase().then(() => {
      void ensurePersistentStorage();

      if (active) {
        setReady(true);
      }
    });

    return () => {
      active = false;
    };
  }, []);

  return (
    <BrowserRouter>
      <RecommendationTemplatesProvider>
        <AppRoutes ready={ready} />
      </RecommendationTemplatesProvider>
    </BrowserRouter>
  );
}
