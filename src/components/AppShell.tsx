import type { ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router-dom';

const navItems = [
  {
    to: '/today',
    label: '오늘',
    detail: '실행'
  },
  {
    to: '/routines',
    label: '루틴',
    detail: '설계'
  },
  {
    to: '/history',
    label: '기록',
    detail: '회고'
  },
  {
    to: '/settings',
    label: '설정',
    detail: '관리'
  }
] as const;

const titleMap: Array<{ match: string; title: string; subtitle: string }> = [
  {
    match: '/setup',
    title: '오늘운동',
    subtitle: '내 운동 흐름을 가볍게 시작해요'
  },
  {
    match: '/today/session/',
    title: '오늘 기록',
    subtitle: '지금 한 운동만 빠르게 남겨요'
  },
  {
    match: '/today',
    title: '오늘',
    subtitle: '오늘 운동과 최근 흐름을 함께 봐요'
  },
  {
    match: '/routines',
    title: '루틴',
    subtitle: '웨이트, 맨몸운동, 유산소를 정리해요'
  },
  {
    match: '/history/',
    title: '상세 기록',
    subtitle: '지난 운동을 짧게 다시 확인해요'
  },
  {
    match: '/history',
    title: '기록',
    subtitle: '기록 변화와 건강 흐름을 함께 봐요'
  },
  {
    match: '/settings',
    title: '설정',
    subtitle: '백업과 보관 상태를 한 번에 봐요'
  }
];

function getHeaderCopy(pathname: string): { title: string; subtitle: string } {
  return (
    titleMap.find((item) => pathname.startsWith(item.match)) ?? {
      title: '오늘운동',
      subtitle: '운동 흐름과 기록을 한 곳에서 봐요'
    }
  );
}

interface AppShellProps {
  children: ReactNode;
}

export function AppShell({ children }: AppShellProps) {
  const location = useLocation();
  const isSetup = location.pathname === '/setup';
  const headerCopy = getHeaderCopy(location.pathname);

  return (
    <div className="app-shell">
      <div className="app-shell__ornament app-shell__ornament--top" />
      <div className="app-shell__ornament app-shell__ornament--bottom" />

      <header className="app-header">
        <div className="app-header__badge">오늘운동</div>
        <h1>{headerCopy.title}</h1>
        <p>{headerCopy.subtitle}</p>
      </header>

      <main className="app-content">{children}</main>

      {!isSetup ? (
        <nav className="bottom-nav" aria-label="주요 메뉴">
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => `bottom-nav__item${isActive ? ' is-active' : ''}`}
            >
              <span className="bottom-nav__detail">{item.detail}</span>
              <span className="bottom-nav__label">{item.label}</span>
            </NavLink>
          ))}
        </nav>
      ) : null}
    </div>
  );
}
