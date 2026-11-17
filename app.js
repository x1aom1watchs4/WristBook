Page({
  data: {
    title: 'WristBook',
    status: 'Ожидание синхронизации',
    bookTitle: 'Нет выбранной книги',
    progress: 0
  },
  onInit() {},
  onShow() {},
  openLibrary() {
    this.setData({ status: 'Библиотека пока не синхронизирована' });
  }
});
