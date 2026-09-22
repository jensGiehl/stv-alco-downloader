document.querySelectorAll('[data-filter-input]').forEach(input => {
    const key = input.dataset.filterInput;
    const list = document.querySelector(`[data-filter-list="${key}"]`);
    if (!list) {
        return;
    }
    const items = Array.from(list.querySelectorAll('[data-filter-item]'));
    const empty = list.parentElement.querySelector('.filter-empty');
    input.addEventListener('input', () => {
        const query = input.value.trim().toLocaleLowerCase('de');
        let visible = 0;
        items.forEach(item => {
            const matches = !query || item.dataset.search.toLocaleLowerCase('de').includes(query);
            item.classList.toggle('d-none', !matches);
            visible += matches ? 1 : 0;
        });
        empty?.classList.toggle('d-none', visible !== 0);
    });
});
