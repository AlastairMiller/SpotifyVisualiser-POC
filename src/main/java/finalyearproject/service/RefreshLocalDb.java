package finalyearproject.service;


import com.wrapper.spotify.Api;
import com.wrapper.spotify.methods.*;
import com.wrapper.spotify.models.FeaturedPlaylists;
import com.wrapper.spotify.models.SimplePlaylist;
import com.wrapper.spotify.models.Track;
import com.wrapper.spotify.models.User;
import finalyearproject.model.Playlist;
import finalyearproject.repository.ArtistRepository;
import finalyearproject.repository.PlaylistRepository;
import finalyearproject.repository.SongRepository;
import finalyearproject.repository.UserRepository;
import finalyearproject.utilities.DownstreamMapper;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Component
@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
public class RefreshLocalDb {

    PlaylistRepository playlistRepository;
    AuthenticationService authenticationService;
    UserRepository userRepository;
    SongRepository songRepository;
    ArtistRepository artistRepository;

    @Autowired
    public RefreshLocalDb(AuthenticationService authenticationService, PlaylistRepository playlistRepository, UserRepository userRepository, SongRepository songRepository, ArtistRepository artistRepository) {
        this.authenticationService = authenticationService;
        this.playlistRepository = playlistRepository;
        this.userRepository = userRepository;
        this.songRepository = songRepository;
        this.artistRepository = artistRepository;
    }


    @Scheduled(fixedDelay = 10000)
    public void main() {
        Api api = authenticationService.clientCredentialflow();
        log.info("------------------Database refresh started-----------------------");
        List<Playlist> playlistsToPull = playlistRepository.findByName(null);
        pullScheduledPlaylists(api);
        if (playlistsToPull.size() > 0) {
            for (Playlist aPlaylistToPull : playlistsToPull) {
                pullPlaylist(api, aPlaylistToPull);
            }
        } else {
            log.info("No updates needed to local DB");
        }
    }

    private void pullScheduledPlaylists(Api api) {
        log.info("Pulling featured Playlists");
        Date timestamp = new Date();
        FeaturedPlaylists featuredPlaylists;

        final FeaturedPlaylistsRequest request = api.getFeaturedPlaylists()
                .limit(20)
                .offset(1)
                .country("GB")
                .timestamp(timestamp)
                .build();
        try {
            featuredPlaylists = request.get();
            List<SimplePlaylist> featuredPlaylistList = featuredPlaylists.getPlaylists().getItems();
            for (SimplePlaylist simplePlaylist : featuredPlaylistList) {
                com.wrapper.spotify.models.Playlist fullPlaylist = convertSimplePlayliststoPlaylists(api, simplePlaylist);

                if (fullPlaylist != null) {

                    Playlist newPlaylist = DownstreamMapper.mapPlaylist(fullPlaylist);

                    if (userRepository.findById(fullPlaylist.getOwner().getId()) == null) {

                        finalyearproject.model.User newUser = DownstreamMapper.mapUser(pullUser(api, fullPlaylist.getOwner().getId()));
                        userRepository.saveAndFlush(newUser);

                    }
                    ArrayList<String> songIdArrayList = new ArrayList<String>();
                    for (int i = 0; i < fullPlaylist.getTracks().getItems().size(); i++) {
                        if (songRepository.findById(fullPlaylist.getTracks().getItems().get(i).getTrack().getId()) == null) {
                            songIdArrayList.add(fullPlaylist.getTracks().getItems().get(i).getTrack().getId());

                        }
                    }
                    if (songIdArrayList.size() > 0) {
                        List<Track> trackArrayList = pullSongs(api, songIdArrayList);
                        if (trackArrayList != null) {
                            ArrayList<String> artistIdArrayList = new ArrayList<String>();
                            for (Track track : trackArrayList) {
                                if (track != null) {
                                    for (int p = 0; p < track.getArtists().size(); p++) {
                                        if (artistRepository.findById(track.getArtists().get(p).getId()) == null) {
                                            artistIdArrayList.add(track.getArtists().get(p).getId());
                                        }
                                    }
                                }
                            }

                            List<com.wrapper.spotify.models.Artist> artistList = pullArtists(api, artistIdArrayList);
                            if (artistList != null) {
                                for (com.wrapper.spotify.models.Artist artist : artistList) {
                                    artistRepository.saveAndFlush(DownstreamMapper.mapArtist(artist));
                                    log.info("Saved Artist: {} to the database", artist.getName());
                                }
                            }

                            for (Track track : trackArrayList) {
                                songRepository.saveAndFlush(DownstreamMapper.mapSong(track));
                                log.info("Saved Song: {} to the database", track.getName());
                            }
                        }
                    }


                    playlistRepository.saveAndFlush(newPlaylist);
                    log.info("Saved a playlist called {}", newPlaylist.getName());
                }
            }
        } catch (Exception e) {
            log.error("Failed to pull featured Playlists from Spotify Api, HTTP Status code: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    private User pullUser(Api api, String id) {
        final UserRequest request = api.getUser(id).build();
        try {
            return request.get();
        } catch (Exception e) {
            log.error("Cannot download user metadata with id {}, HTTP Status code: {}", id, e.getMessage());
            return null;
        }
    }

    public static Track pullSong(Api api, String id) {
        final TrackRequest request = api.getTrack(id).build();
        try {
            return request.get();
        } catch (Exception e) {
            log.error("Cannot download metadata for song with id {}, HTTP Status code: {}", id, e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public static List<Track> pullSongs(Api api, ArrayList<String> ids) {
        List<Track> pulledTracks = new ArrayList<Track>();
        if (ids.size() > 50) {
            final TracksRequest request = api.getTracks(ids.subList(0, 49)).build();
            ids = new ArrayList<String>(ids.subList(49, ids.size()));
            try {
                pulledTracks.addAll(request.get());
            } catch (Exception e) {
                log.error("Cannot retrieve batch of songs from api, HTTP Status code: {}", e.getMessage());
                return null;
            }
            pulledTracks.addAll(pullSongs(api, ids));
            return pulledTracks;
        } else {
            final TracksRequest request = api.getTracks(ids).build();
            try {
                pulledTracks.addAll(request.get());
                return pulledTracks;
            } catch (Exception e) {
                log.error("Cannot retrieve batch of songs from api, HTTP error code: {}", e.getMessage());
                return null;
            }
        }
    }

    public static com.wrapper.spotify.models.Artist pullArtist(Api api, String id) {
        final ArtistRequest request = api.getArtist(id).build();
        try {
            return request.get();
        } catch (Exception e) {
            log.error("Failed to download metadata for artist {}, HTTP error code: {} ", id, e.getMessage());
            return null;
        }

    }

    public static List<com.wrapper.spotify.models.Artist> pullArtists(Api api, List<String> ids) {
        List<com.wrapper.spotify.models.Artist> pulledArtists = new ArrayList<com.wrapper.spotify.models.Artist>();
        if (ids.size() > 50) {
            final ArtistsRequest request = api.getArtists(ids.subList(0, 49)).build();
            ids = new ArrayList<String>(ids.subList(49, ids.size()));
            try {
                pulledArtists.addAll(request.get());
            } catch (Exception e) {
                log.error("Cannot retrieve batch of Artists. HTTP error code: {}", e.getMessage());
            }
            pulledArtists.addAll(pullArtists(api, ids));
            return pulledArtists;
        } else {
            final ArtistsRequest request = api.getArtists(ids).build();
            try {
                pulledArtists.addAll(request.get());
                return pulledArtists;
            } catch (Exception e) {
                log.error("Cannot retrieve batch of Artists. HTTP error code: {}", e.getMessage());
                return null;
            }
        }
    }

    private com.wrapper.spotify.models.Playlist convertSimplePlayliststoPlaylists(Api api, SimplePlaylist simplePlaylist) {
        return pullPlaylist(api, simplePlaylist.getOwner().getId(), simplePlaylist.getId());
    }


    com.wrapper.spotify.models.Playlist pullPlaylist(Api api, Playlist playlistToPull) {
        return pullPlaylist(api, playlistToPull.getOwner().getId(), playlistToPull.getId());
    }

    com.wrapper.spotify.models.Playlist pullPlaylist(Api api, String ownerId, String playlistId) {
        final PlaylistRequest request = api.getPlaylist(ownerId, playlistId).build();
        try {
            return request.get();
        } catch (Exception e) {
            log.error("Failed to download metadata for playlist {}, HTTP error code: {}", playlistId, e.getMessage());
        }
        return null;
    }

}
