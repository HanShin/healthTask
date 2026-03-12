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
    title: '한신의 운동일지',
    subtitle: '개인 루틴을 모바일에 맞게 세팅합니다'
  },
  {
    match: '/today/session/',
    title: '운동 기록',
    subtitle: '세트와 페이스를 지금 바로 남겨두세요'
  },
  {
    match: '/today',
    title: '오늘의 흐름',
    subtitle: '지금 해야 할 루틴을 한 화면에서 정리합니다'
  },
  {
    match: '/routines',
    title: '루틴 스튜디오',
    subtitle: '내 방식에 맞춘 루틴을 만들고 다듬습니다'
  },
  {
    match: '/history/',
    title: '세션 상세',
    subtitle: '지난 운동을 세트 단위로 되짚어봅니다'
  },
  {
    match: '/history',
    title: '기록 아카이브',
    subtitle: '꾸준함과 변화를 날짜별로 확인합니다'
  },
  {
    match: '/settings',
    title: '설정',
    subtitle: '백업과 설치 환경을 관리합니다'
  }
];

function getHeaderCopy(pathname: string): { title: string; subtitle: string } {
  return (
    titleMap.find((item) => pathname.startsWith(item.match)) ?? {
      title: '한신의 운동일지',
      subtitle: '운동 루틴과 러닝 기록을 한 곳에서 관리합니다'
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
        <div className="app-header__badge">개인 운동 기록 앱</div>
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
