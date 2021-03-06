package pasta.streamer.fragments;

import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.graphics.Palette;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.afollestad.async.Action;
import com.afollestad.async.Async;
import com.afollestad.async.Pool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import butterknife.Bind;
import butterknife.ButterKnife;
import kaaes.spotify.webapi.android.SpotifyService;
import kaaes.spotify.webapi.android.models.Album;
import kaaes.spotify.webapi.android.models.Artist;
import kaaes.spotify.webapi.android.models.Artists;
import kaaes.spotify.webapi.android.models.Pager;
import pasta.streamer.Pasta;
import pasta.streamer.R;
import pasta.streamer.adapters.SectionedOmniAdapter;
import pasta.streamer.data.AlbumListData;
import pasta.streamer.data.ArtistListData;
import pasta.streamer.data.PlaylistListData;
import pasta.streamer.data.TrackListData;
import pasta.streamer.utils.Downloader;
import pasta.streamer.utils.Settings;
import pasta.streamer.utils.StaticUtils;
import pasta.streamer.views.CustomImageView;

public class ArtistFragment extends FullScreenFragment {

    @Bind(R.id.progressBar2)
    ProgressBar spinner;
    @Bind(R.id.collapsing_toolbar)
    CollapsingToolbarLayout collapsingToolbarLayout;
    @Bind(R.id.topTenTrackListView)
    RecyclerView recycler;
    @Bind(R.id.header)
    CustomImageView header;
    @Bind(R.id.title)
    TextView title;
    @Bind(R.id.subtitle)
    TextView subtitle;
    @Bind(R.id.extra)
    TextView extra;
    @Bind(R.id.toolbar)
    Toolbar toolbar;
    @Bind(R.id.somethingbar)
    View somethingbar;
    @Nullable @Bind(R.id.backgroundImage)
    CustomImageView backgroundImage;

    private ArtistListData data;
    private SectionedOmniAdapter adapter;
    private GridLayoutManager manager;
    private Pool pool;
    private boolean palette;
    private Pasta pasta;
    private Map<String, Object> limitMap;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = DataBindingUtil.inflate(inflater, R.layout.fragment_artist, container, false).getRoot();
        ButterKnife.bind(this, rootView);

        data = getArguments().getParcelable("artist");

        palette = Settings.isPalette(getContext());
        pasta = (Pasta) getContext().getApplicationContext();
        limitMap = new HashMap<>();
        limitMap.put(SpotifyService.LIMIT, (Settings.getLimit(getContext()) + 1) * 10);

        setHasOptionsMenu(true);
        toolbar.setNavigationIcon(R.drawable.drawer_back);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getActivity().onBackPressed();
            }
        });

        toolbar.inflateMenu(R.menu.menu_basic);
        modifyMenu(toolbar.getMenu());
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                onMenuClick(item);
                return false;
            }
        });

        title.setText(data.artistName);
        subtitle.setText(data.genres);
        extra.setText(String.valueOf(data.followers) + " followers");

        spinner.setVisibility(View.VISIBLE);

        manager = new GridLayoutManager(getContext(), Settings.getColumnNumber(getContext(), false));
        if (Settings.isCards(getContext())) {
            manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    return manager.getSpanCount();
                }
            });
        } else {
            manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    if (adapter.getItemViewType(position) == 0 || adapter.getItemViewType(position) == 4) return manager.getSpanCount();
                    else return 1;
                }
            });
        }
        recycler.setLayoutManager(manager);


        adapter = new SectionedOmniAdapter((AppCompatActivity) getActivity(), null);
        recycler.setAdapter(adapter);

        pool = Async.parallel(new Action<ArrayList<TrackListData>>() {
            @NonNull
            @Override
            public String id() {
                return "searchTracks";
            }

            @Nullable
            @Override
            protected ArrayList<TrackListData> run() throws InterruptedException {
                return pasta.getTracks(data);
            }

            @Override
            protected void done(@Nullable ArrayList<TrackListData> result) {
                if (spinner != null) spinner.setVisibility(View.GONE);
                if (result == null) pasta.onNetworkError(getActivity());
                else adapter.addData(result);
            }
        }, new Action<ArrayList<String>>() {
            @NonNull
            @Override
            public String id() {
                return "searchAlbums";
            }

            @Nullable
            @Override
            protected ArrayList<String> run() throws InterruptedException {
                Pager<Album> albums = getAlbums();
                if (albums == null) return null;

                ArrayList<String> list = new ArrayList<>();
                for (Album album : albums.items) {
                    list.add(album.id);
                }

                return list;
            }

            @Nullable
            private Pager<Album> getAlbums() throws InterruptedException {
                Pager<Album> albums = null;
                for (int i = 0; albums == null && i < Settings.getRetryCount(getContext()); i++) {
                    try {
                        albums = pasta.spotifyService.getArtistAlbums(data.artistId);
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (StaticUtils.shouldResendRequest(e)) Thread.sleep(200);
                        else break;
                    }
                }
                return albums;
            }

            @Override
            protected void done(@Nullable ArrayList<String> result) {
                if (result == null) {
                    pasta.onNetworkError(getActivity());
                    return;
                }
                for (final String id : result) {
                    new Action<AlbumListData>() {
                        @NonNull
                        @Override
                        public String id() {
                            return "getAlbum";
                        }

                        @Nullable
                        @Override
                        protected AlbumListData run() throws InterruptedException {
                            Album album;
                            Artist artist;
                            try {
                                album = pasta.spotifyService.getAlbum(id);
                                artist = pasta.spotifyService.getArtist(album.artists.get(0).id);
                            } catch (Exception e) {
                                e.printStackTrace();
                                return null;
                            }

                            String image = "";
                            if (artist.images.size() > 0) image = artist.images.get(album.images.size() / 2).url;

                            return new AlbumListData(album, image);
                        }

                        @Override
                        protected void done(@Nullable AlbumListData result) {
                            if (spinner != null) spinner.setVisibility(View.GONE);
                            if (result != null) adapter.addData(result);
                        }
                    }.execute();
                }
            }
        }, new Action<ArrayList<PlaylistListData>>() {
            @NonNull
            @Override
            public String id() {
                return "getPlaylists";
            }

            @Nullable
            @Override
            protected ArrayList<PlaylistListData> run() throws InterruptedException {
                return pasta.searchPlaylists(data.artistName, limitMap);
            }

            @Override
            protected void done(@Nullable ArrayList<PlaylistListData> result) {
                if (spinner != null) spinner.setVisibility(View.GONE);
                if (result == null) pasta.onNetworkError(getContext());
                else adapter.addData(result);
            }
        }, new Action<ArrayList<ArtistListData>>() {
            @NonNull
            @Override
            public String id() {
                return "getArtists";
            }

            @Nullable
            @Override
            protected ArrayList<ArtistListData> run() throws InterruptedException {
                Artists artists = getArtists();
                if (artists == null) return null;

                ArrayList<ArtistListData> list = new ArrayList<>();
                for (Artist artist : artists.artists) {
                    ArtistListData artistData = new ArtistListData(artist);
                    list.add(artistData);
                }

                return list;
            }

            @Nullable
            private Artists getArtists() throws InterruptedException {
                Artists artists = null;
                for (int i = 0; artists == null && i < Settings.getRetryCount(getContext()); i++) {
                    try {
                        artists = pasta.spotifyService.getRelatedArtists(data.artistId);
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (StaticUtils.shouldResendRequest(e)) Thread.sleep(200);
                        else break;
                    }
                }
                return artists;
            }

            @Override
            protected void done(@Nullable ArrayList<ArtistListData> result) {
                if (spinner != null) spinner.setVisibility(View.GONE);
                if (result == null) pasta.onNetworkError(getContext());
                else adapter.addData(result);
            }
        }, new Action<Bitmap>() {
            @NonNull
            @Override
            public String id() {
                return "getArtistHeader";
            }

            @Nullable
            @Override
            protected Bitmap run() throws InterruptedException {
                return Downloader.downloadImage(getContext(), data.artistImage);
            }

            @Override
            protected void done(@Nullable Bitmap result) {
                if (result == null) return;

                header.transition(new BitmapDrawable(getResources(), result));

                if (backgroundImage != null) backgroundImage.transition(new BitmapDrawable(getResources(), StaticUtils.blurBitmap(result)));

                if (!palette) return;
                Palette.from(result).generate(new Palette.PaletteAsyncListener() {
                    @Override
                    public void onGenerated(Palette palette) {
                        int primary = palette.getMutedColor(Color.GRAY);
                        collapsingToolbarLayout.setContentScrimColor(primary);
                        TransitionDrawable td = new TransitionDrawable(new Drawable[]{somethingbar.getBackground(), new ColorDrawable(primary)});
                        somethingbar.setBackground(td);
                        td.startTransition(250);
                        setData(data.artistName, primary, palette.getDarkVibrantColor(primary));
                    }
                });
            }
        });

        return rootView;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_basic, menu);
        modifyMenu(menu);

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        onMenuClick(item);
        return false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (pool != null && pool.isExecuting()) pool.cancel();
        ButterKnife.unbind(this);
    }

    private void modifyMenu(final Menu menu) {
        new Action<Boolean>() {
            @NonNull
            @Override
            public String id() {
                return "isArtistFav";
            }

            @Nullable
            @Override
            protected Boolean run() throws InterruptedException {
                try {
                    return pasta.isFavorite(data);
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }

            @Override
            protected void done(@Nullable Boolean result) {
                if (result == null) {
                    pasta.onNetworkError(getActivity());
                    return;
                }
                if (result) {
                    menu.findItem(R.id.action_fav).setIcon(R.drawable.ic_fav);
                } else {
                    menu.findItem(R.id.action_fav).setIcon(R.drawable.ic_unfav);
                }
            }

        }.execute();
    }

    private void onMenuClick(final MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                getActivity().onBackPressed();
                break;
            case R.id.action_fav:
                new Action<Boolean>() {
                    @NonNull
                    @Override
                    public String id() {
                        return "favArtist";
                    }

                    @Nullable
                    @Override
                    protected Boolean run() throws InterruptedException {
                        if (!pasta.toggleFavorite(data)) {
                            return null;
                        } else return pasta.isFavorite(data);
                    }

                    @Override
                    protected void done(@Nullable Boolean result) {
                        if (result == null) {
                            pasta.onNetworkError(getActivity());
                            return;
                        }
                        if (result) {
                            item.setIcon(R.drawable.ic_fav);
                        } else {
                            item.setIcon(R.drawable.ic_unfav);
                        }
                    }

                }.execute();
                break;
            case R.id.action_share:
                Intent s = new Intent(android.content.Intent.ACTION_SEND);
                s.setType("text/plain");
                s.putExtra(Intent.EXTRA_SUBJECT, data.artistName);
                s.putExtra(Intent.EXTRA_TEXT, StaticUtils.getArtistUrl(data.artistId));
                startActivity(Intent.createChooser(s, data.artistName));
                break;
            case R.id.action_web:
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(StaticUtils.getArtistUrl(data.artistId))));
                break;
        }
    }
}
