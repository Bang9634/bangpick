// 폐기 가능한 시연 전용 데이터. 실제 매물이나 서비스 API와 연결하지 않는다.
export const demoListings = [
  {
    id: 'yeonnam',
    name: '연남동 햇살 원룸',
    area: '연남동',
    price: '500 / 55',
    address: '서울 마포구 연남동 일대',
    station: '홍대입구역 도보 8분',
    size: '20.4㎡',
    floor: '2층 / 4층',
    maintenance: '7만원',
    description: '큰 창으로 빛이 들어오는 아늑한 원룸. 생활 공간과 주방이 이어지는 구조를 직접 둘러보세요.',
    options: ['에어컨', '냉장고', '세탁기', '인덕션'],
    position: [37.5628, 126.9236] as [number, number],
  },
  {
    id: 'seogyo',
    name: '서교동 조용한 스튜디오',
    area: '서교동',
    price: '1,000 / 65',
    address: '서울 마포구 서교동 일대',
    station: '홍대입구역 도보 6분',
    size: '24.1㎡',
    floor: '3층 / 5층',
    maintenance: '8만원',
    description: '일상에 필요한 공간을 담은 스튜디오. 지도에서 위치를 고르고 3D 투어로 이어지는 흐름을 확인해 보세요.',
    options: ['에어컨', '냉장고', '세탁기', '전자레인지'],
    position: [37.5546, 126.9221] as [number, number],
  },
  {
    id: 'hapjeong',
    name: '합정동 포근한 원룸',
    area: '합정동',
    price: '500 / 60',
    address: '서울 마포구 합정동 일대',
    station: '합정역 도보 5분',
    size: '22.0㎡',
    floor: '2층 / 3층',
    maintenance: '6만원',
    description: '차분하게 머물기 좋은 원룸을 가정한 시연 매물입니다. 아래 버튼에서 준비한 방 모델을 열 수 있습니다.',
    options: ['에어컨', '냉장고', '세탁기', '옷장'],
    position: [37.5493, 126.9138] as [number, number],
  },
]

export type DemoListing = (typeof demoListings)[number]

// 세 매물은 같은 시연 파일을 연다. 실제 파일·카메라 정보를 받으면 이 설정만 조정한다.
export const demoModel = {
  url: `${import.meta.env.BASE_URL}demo/room.ply`,
  rotation: [Math.PI, 0, 0] as [number, number, number],
  initialPosition: null as [number, number, number] | null,
  initialTarget: null as [number, number, number] | null,
  moveSpeed: 1.2,
}
